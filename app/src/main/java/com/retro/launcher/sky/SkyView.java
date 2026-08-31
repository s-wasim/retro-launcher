package com.retro.launcher.sky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

import com.retro.launcher.core.MoonPhase;
import com.retro.launcher.core.SkyRenderer;

import java.util.Calendar;

/**
 * The animated pixel sky: a small buffer rendered by {@link SkyRenderer} and
 * nearest-neighbour upscaled to fill the view. See DESIGN_NOTES §2b.
 *
 * A dedicated render thread keeps the per-pixel work off the UI thread. It is
 * started in onSurfaceTextureAvailable, stopped by a volatile flag in
 * onSurfaceTextureDestroyed, and gated by pause()/resume() so it never runs
 * while another app is foreground.
 *
 * {@link TextureView} rather than {@code SurfaceView}: a SurfaceView's
 * pixels are composited by SurfaceFlinger as an independent hole-punch
 * layer, outside the RenderThread pipeline that draws and animates the
 * panels sliding above it. Under a slow panel drag the two compositors can
 * fall a frame out of lockstep, briefly showing a stale hole-punch frame —
 * the panel's own last opaque content — bleeding through behind the live,
 * correctly-composited panel: the "ghost panel" artifact. A TextureView
 * composites as a normal GPU texture inside the same RenderThread pipeline
 * as every other view, so there is only one compositor and no desync is
 * possible. See docs/superpowers/specs/2026-08-31-panel-fixes-and-launcher-controls-design.md §1.
 */
public final class SkyView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final int BUF_W = 108;
    private static final long FRAME_BUDGET_MS = 33L; // ~30fps

    private final Paint paint = new Paint();
    private final Rect dst = new Rect();

    private SkyRenderer renderer;
    private Bitmap bitmap;
    private int[] buf;
    private int bufH;

    private volatile boolean surfaceReady;
    private volatile boolean running;
    private Thread renderThread;

    private volatile float weather;
    private volatile int[] tintRamp;
    private volatile float desaturation;
    private volatile float latitude = Float.NaN;   // no fix yet

    private final long startNanos = System.nanoTime();

    public SkyView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        setSurfaceTextureListener(this);
    }

    public void setWeather(float w) { this.weather = w; }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) { this.desaturation = amount; }

    /** The latitude of the coarse fix the weather already keeps, which is all
     *  the moon needs: it decides which way up the phase is drawn.
     *  {@code Float.NaN} means "no fix" and reads as the northern view. */
    public void setLatitude(float degrees) { this.latitude = degrees; }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surfaceReady = true;
        resize(width, height);
        startThreadIfNeeded();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        resize(width, height);
    }

    private void resize(int w, int h) {
        int bh = Math.round(BUF_W * (float) h / Math.max(1, w));
        bh = Math.max(96, Math.min(320, bh));
        if (renderer == null || bh != bufH) {
            bufH = bh;
            buf = new int[BUF_W * bufH];
            bitmap = Bitmap.createBitmap(BUF_W, bufH, Bitmap.Config.ARGB_8888);
            renderer = new SkyRenderer(BUF_W, bufH);
        }
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        surfaceReady = false;
        stopThread();
        return true; // we're done with the SurfaceTexture; safe to release
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Fires after every unlockCanvasAndPost — nothing to do here.
    }

    public void pause() { stopThread(); }

    public void resume() { startThreadIfNeeded(); }

    private void startThreadIfNeeded() {
        if (running || !surfaceReady) return;
        running = true;
        renderThread = new Thread(this::loop, "SkyRenderThread");
        renderThread.start();
    }

    private void stopThread() {
        running = false;
        Thread t = renderThread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        renderThread = null;
    }

    private void loop() {
        while (running) {
            long frameStart = System.currentTimeMillis();
            drawFrame();
            long elapsed = System.currentTimeMillis() - frameStart;
            long sleep = FRAME_BUDGET_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void drawFrame() {
        SkyRenderer r = renderer;
        Bitmap bmp = bitmap;
        if (r == null || bmp == null) return;

        r.setTint(tintRamp);
        r.setDesaturation(desaturation);
        r.setSouthernView(MoonPhase.southernView(latitude));

        float hour = decimalHour();
        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        // Seven sines a frame against a 108xN pixel loop — not worth caching,
        // and recomputing means the terminator creeps in real time.
        float moonPhase = MoonPhase.phase(System.currentTimeMillis());
        r.render(buf, hour, weather, moonPhase, seconds);
        bmp.setPixels(buf, 0, BUF_W, 0, 0, BUF_W, bufH);

        Canvas canvas = null;
        try {
            canvas = lockCanvas();
            if (canvas == null) return;
            dst.set(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bmp, null, dst, paint);
        } finally {
            if (canvas != null) {
                try { unlockCanvasAndPost(canvas); } catch (IllegalArgumentException ignored) { /* surface gone */ }
            }
        }
    }

    private static float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }
}
