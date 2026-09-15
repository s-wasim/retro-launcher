package com.retro.launcher.sky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

import com.retro.launcher.core.FrameBudget;
import com.retro.launcher.core.MoonPhase;
import com.retro.launcher.core.SkyConditions;
import com.retro.launcher.core.SkyRenderer;
import com.retro.launcher.core.SolarClock;
import com.retro.launcher.core.SolarTimes;
import com.retro.launcher.core.Weather;

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
 * possible. See DESIGN_NOTES §9 delta 17.
 */
public final class SkyView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final int BUF_W = 108;

    private final Paint paint = new Paint();
    private final Rect dst = new Rect();

    private SkyRenderer renderer;
    private Bitmap bitmap;
    private int[] buf;
    private int bufH;

    private volatile boolean surfaceReady;
    private volatile boolean running;
    private Thread renderThread;

    private volatile Weather weather;
    private volatile boolean manualOverrideEnabled;
    private volatile float manualHour;
    private volatile float manualMoonPhase;
    private volatile int[] tintRamp;
    private volatile float desaturation;
    private volatile float latitude = Float.NaN;   // no fix yet
    private volatile float longitude = Float.NaN;  // no fix yet
    private volatile SolarTimes solarTimes;         // null until known

    /** True while a panel is drawn over the sky. See {@link #setObscured}. */
    private volatile boolean obscured;

    /** The sleep the last frame's conditions earned, so {@link #loop} does
     *  not have to rebuild a SkyConditions just to decide how long to wait. */
    private volatile long frameIntervalMs = FrameBudget.SLOW_MS;

    /**
     * Guards the render thread's sleep so it can be cut short.
     *
     * <p>2.1.3 needs this. The loop used to {@code Thread.sleep(33)}, so
     * {@code stopThread}'s {@code join(500)} always had fifteen chances to
     * catch it and the thread was gone long before the timeout. Sleeps are now
     * up to {@link FrameBudget#IDLE_MS}, which is ten times that timeout: the
     * join would give up while the thread slept on, {@code renderThread} would
     * be nulled, and the next {@code resume()} would start a <em>second</em>
     * render thread with the first still alive and still about to call
     * {@code lockCanvas}. Waiting on a monitor instead means stopping wakes
     * the thread immediately rather than waiting it out.
     */
    private final Object sleepLock = new Object();

    /** Set under {@link #sleepLock} to cut a sleep short. Distinguishes a
     *  real wake from a spurious one, which {@code Object.wait} is allowed to
     *  produce and which must go back to sleeping. */
    private boolean wakeRequested;

    private final long startNanos = System.nanoTime();

    public SkyView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        setSurfaceTextureListener(this);
    }

    public void setWeather(Weather w) { this.weather = w; }

    /** V9 §7b: when enabled, {@code hour} is fed directly as the sky's
     *  already-"warped" hour (bypassing {@link SolarClock#warp} so the full
     *  0-24 keyframe range is directly scrubbable) and the moon is always
     *  visible across the whole knob range rather than gated by a real
     *  moonrise/moonset window. */
    public void setManualOverride(boolean enabled, float hour, float moonPhase) {
        this.manualOverrideEnabled = enabled;
        this.manualHour = hour;
        this.manualMoonPhase = moonPhase;
    }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) { this.desaturation = amount; }

    /**
     * The coarse fix the weather already keeps. Latitude decides which way
     * up the moon's phase is drawn ({@code Float.NaN} means "no fix" and
     * reads as the northern view); both feed {@link SolarClock}'s time warp
     * once {@link #setSolarTimes} has a value.
     */
    public void setLocation(float latitudeDegrees, float longitudeDegrees) {
        this.latitude = latitudeDegrees;
        this.longitude = longitudeDegrees;
    }

    /** Today's sunrise/sunset, or null when none is known yet — the sky then
     *  draws against the fixed 6.2/18.4 table instead of a warped one. */
    public void setSolarTimes(SolarTimes times) { this.solarTimes = times; }

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

    /**
     * Whether a panel is currently covering the sky.
     *
     * <p>The launcher's other three panels are opaque and full-screen, so for
     * most of the time the drawer or Settings is open the renderer is drawing
     * frames nobody can see. {@link FrameBudget#OBSCURED_FLOOR_MS} caps the
     * rate at 1fps rather than stopping outright: a panel can be flung away
     * in a single frame, and the sky behind it should already be current when
     * it goes — and the panels are translucent for part of every transition.
     */
    public void setObscured(boolean covered) {
        boolean wasObscured = this.obscured;
        this.obscured = covered;
        // Uncovering is the case that must not wait: the sky may be a full
        // second into a 1fps sleep at the moment the panel leaves.
        if (wasObscured && !covered) wake();
    }

    private void startThreadIfNeeded() {
        if (running || !surfaceReady) return;
        running = true;
        renderThread = new Thread(this::loop, "SkyRenderThread");
        renderThread.start();
    }

    private void stopThread() {
        running = false;
        wake();   // cut short whatever sleep it is in, up to IDLE_MS long
        Thread t = renderThread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        renderThread = null;
    }

    /**
     * Sleeps up to {@code ms}, returning early if the thread is stopped or
     * {@link #wake()} is called. Loops on the deadline rather than trusting
     * one {@code wait} to have run its full term, because a spurious wakeup
     * would otherwise turn a 4fps scene into a spin.
     */
    private void sleepFor(long ms) {
        synchronized (sleepLock) {
            long deadline = System.currentTimeMillis() + ms;
            while (running && !wakeRequested) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                try {
                    sleepLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            wakeRequested = false;
        }
    }

    /** Ends the render thread's current sleep. Safe from any thread. */
    private void wake() {
        synchronized (sleepLock) {
            wakeRequested = true;
            sleepLock.notifyAll();
        }
    }

    /**
     * 2.1.3: the interval is no longer fixed.
     *
     * <p>This used to sleep a flat 33ms — ~30fps for as long as the launcher
     * was foreground, whatever was on screen. {@link FrameBudget} now derives
     * it from the scene, and because {@link #drawFrame} is what builds the
     * conditions, it is also what publishes the next interval; the loop just
     * reads what the last frame decided. A clear sky settles at 4fps and rain
     * at 24, against 30 for both before.
     *
     * <p>A long idle sleep is not a problem for responsiveness: nothing waits
     * on this thread. A change that needs to be seen immediately — a palette
     * switch, the manual override moving — goes through a setter on the UI
     * thread and lands in the volatile fields this loop reads on its next
     * pass. The worst case is that it appears up to one interval late, which
     * at the rates above is a quarter of a second on a scene where nothing is
     * moving anyway.
     */
    private void loop() {
        while (running) {
            long frameStart = System.currentTimeMillis();
            drawFrame();
            long elapsed = System.currentTimeMillis() - frameStart;
            long sleep = frameIntervalMs - elapsed;
            if (sleep > 0) sleepFor(sleep);
        }
    }

    private void drawFrame() {
        SkyRenderer r = renderer;
        Bitmap bmp = bitmap;
        Weather w = weather;
        if (r == null || bmp == null || w == null) {
            // No reading yet, so there is nothing to draw and nothing to
            // derive an interval from. Idle rather than spin at whatever the
            // previous scene happened to earn.
            frameIntervalMs = FrameBudget.IDLE_MS;
            return;
        }

        r.setTint(tintRamp);
        r.setDesaturation(desaturation);
        r.setSouthernView(MoonPhase.southernView(latitude));

        float realHour = decimalHour();
        float hour, moonriseHour, moonsetHour, moonPhase;
        if (manualOverrideEnabled) {
            hour = manualHour;
            realHour = manualHour;
            moonriseHour = 0f;
            moonsetHour = 24f;
            moonPhase = manualMoonPhase;
        } else {
            SolarTimes times = solarTimes;
            hour = times == null
                    ? realHour
                    : SolarClock.warp(realHour, times.sunriseHour, times.sunsetHour, times.tomorrowSunriseHour);
            moonriseHour = times == null ? Float.NaN : times.moonriseHour;
            moonsetHour = times == null ? Float.NaN : times.moonsetHour;
            moonPhase = MoonPhase.phase(System.currentTimeMillis());
        }

        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        SkyConditions c = new SkyConditions(hour, realHour, moonriseHour, moonsetHour,
                w.cloudCover, w.precip, moonPhase, w.type, w.thunder, w.tempC);

        // Published for loop() before the frame is drawn rather than after,
        // so a scene that has just turned to rain speeds up on this frame
        // instead of sleeping out one more slow interval first.
        frameIntervalMs = FrameBudget.intervalMs(c, BUF_W, bufH, obscured);

        r.render(buf, c, seconds);
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
