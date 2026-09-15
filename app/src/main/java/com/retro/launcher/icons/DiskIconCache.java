package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.retro.launcher.core.IconCacheKey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The second tier of the icon cache: PNGs under {@code cacheDir}, with the
 * 24-hour TTL from {@link IconCacheKey}.
 *
 * <p><b>Why disk at all.</b> Stage 2 of {@link PixelArtIcons} — the app's own
 * icon, quantized through the palette ramp — is the expensive one: a
 * {@code PackageManager} drawable load, an adaptive-icon crop, 576 nearest-ramp
 * lookups and a scale, per app, and before 2.1.2 it ran again on every cold
 * start and again every time the {@code LruCache} evicted the entry. The
 * memory tier alone cannot fix a cold start, because a cold start has no
 * memory tier. This is what keeps the APK small — no icon ships in the
 * package — while paying the generation cost once a day instead of once a
 * launch. Installed size grows by roughly 100 KB for a few hundred apps,
 * which is the trade §0 row 2 already accepts.
 *
 * <p><b>What is not written here.</b> Stage 1, the hand-drawn marks: they are
 * a few dozen {@code drawRect} calls against a 16x16 grid with no
 * PackageManager round-trip at all, so a PNG decode would cost more than
 * redrawing them. They stay in the memory tier only. Stages 2 and 3 are both
 * written.
 *
 * <p><b>Threading.</b> Reads happen on whatever thread asks — they are a
 * {@code File.lastModified()} and a decode of a ~300-byte PNG, well inside a
 * frame, and doing them asynchronously would mean an icon that pops in after
 * the row is already on screen. Writes go to a single background thread,
 * because they are pure housekeeping and nothing waits on them.
 */
public final class DiskIconCache {

    /** Total bytes of PNG this directory may hold before {@link #sweep()}
     *  starts deleting the oldest. A few hundred 24x24 PNGs come to well
     *  under a tenth of this; the ceiling exists so a device with thousands
     *  of apps, or a palette the user keeps switching, cannot grow the
     *  directory without bound. */
    private static final long BUDGET_BYTES = 4L * 1024L * 1024L;

    /** PNG is lossless, so quality is ignored for it — passed for the API's
     *  sake. Lossless matters: these are quantized to a handful of exact
     *  palette colours and JPEG ringing would undo the quantization. */
    private static final int PNG_QUALITY = 100;

    private final File dir;
    private final ExecutorService writer;

    /** Set once the directory turns out to be unusable — a full disk, a
     *  device whose cache dir the system has yanked. Every method then
     *  no-ops and the launcher runs exactly as it did before 2.1.2. */
    private volatile boolean disabled;

    public DiskIconCache(File cacheDir) {
        this.dir = new File(cacheDir, IconCacheKey.directoryName());
        this.writer = Executors.newSingleThreadExecutor(lowPriorityThreads());
        if (!dir.isDirectory() && !dir.mkdirs()) disabled = true;
    }

    /**
     * Background work here is never urgent and must never compete with the
     * UI thread for a core, so the writer runs below the default priority.
     */
    private static ThreadFactory lowPriorityThreads() {
        return r -> {
            Thread t = new Thread(r, "IconDiskCache");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        };
    }

    /** @return the cached bitmap, or null when there is no fresh entry */
    public Bitmap read(String key) {
        if (disabled) return null;
        File f = fileFor(key);
        long modified;
        try {
            if (!f.isFile()) return null;
            modified = f.lastModified();
        } catch (SecurityException e) {
            return null;
        }
        if (!IconCacheKey.isFresh(modified, System.currentTimeMillis())) {
            delete(f);
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // The stored PNG is already at the resolution we draw from, so
            // nothing may resample it on the way in.
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
            if (bmp == null) delete(f);   // truncated by a kill mid-write
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            delete(f);
            return null;
        }
    }

    /** Fire-and-forget. The caller already has the bitmap it needs. */
    public void write(String key, Bitmap bitmap) {
        if (disabled || bitmap == null || bitmap.isRecycled()) return;
        File target = fileFor(key);
        try {
            writer.execute(() -> writeNow(target, bitmap));
        } catch (RuntimeException ignored) {
            // Executor shut down between the check and the submit.
        }
    }

    /**
     * Writes to a temporary name and renames on success, so a process death
     * mid-write leaves a stray {@code .tmp} rather than a half-written PNG
     * that {@link #read} would have to decode to discover was broken.
     */
    private void writeNow(File target, Bitmap bitmap) {
        File tmp = new File(target.getPath() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            ok = false;
        }
        if (!ok || !tmp.renameTo(target)) {
            delete(tmp);
        }
    }

    /**
     * Deletes expired entries, then the oldest entries until the directory is
     * back inside {@link #BUDGET_BYTES}. Cheap — a directory listing and some
     * {@code lastModified()} calls — but it still runs off the UI thread,
     * queued behind whatever writes are already pending.
     */
    public void sweep() {
        if (disabled) return;
        try {
            writer.execute(this::sweepNow);
        } catch (RuntimeException ignored) {
        }
    }

    private void sweepNow() {
        File[] files = dir.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        long total = 0L;
        for (File f : files) {
            long modified = f.lastModified();
            // A .tmp is a write we did not finish; it is never a cache hit,
            // so it is only ever litter.
            if (f.getName().endsWith(".tmp") || !IconCacheKey.isFresh(modified, now)) {
                delete(f);
                continue;
            }
            total += f.length();
        }
        if (total <= BUDGET_BYTES) return;

        // Over budget: drop the least recently written until we are back
        // under. Sorting by mtime is an approximation of least-recently-used —
        // the real thing would need a touch on every read, which is a write
        // per icon per frame and costs far more than it saves.
        File[] survivors = dir.listFiles();
        if (survivors == null) return;
        java.util.Arrays.sort(survivors, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
        for (File f : survivors) {
            if (total <= BUDGET_BYTES) return;
            long size = f.length();
            if (delete(f)) total -= size;
        }
    }

    /** Drops every stored icon. Not called on a palette change — the palette
     *  is part of the key, so the old files are simply never asked for again
     *  and {@link #sweep()} reclaims them on the TTL. */
    public void clear() {
        if (disabled) return;
        try {
            writer.execute(() -> {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) delete(f);
            });
        } catch (RuntimeException ignored) {
        }
    }

    private File fileFor(String key) {
        return new File(dir, IconCacheKey.fileName(key));
    }

    private static boolean delete(File f) {
        try {
            return f.delete();
        } catch (SecurityException e) {
            return false;
        }
    }
}
