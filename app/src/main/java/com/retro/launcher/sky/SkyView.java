package com.retro.launcher.sky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.retro.launcher.core.SkyRenderer;

import java.util.Calendar;

/**
 * The animated pixel sky: a small buffer rendered by {@link SkyRenderer} and
 * nearest-neighbour upscaled to fill the view. See DESIGN_NOTES §2b.
 *
 * A dedicated render thread keeps the per-pixel work off the UI thread. It is
 * started in surfaceCreated, stopped by a volatile flag in surfaceDestroyed,
 * and gated by pause()/resume() so it never runs while another app is
 * foreground.
 */
public final class SkyView extends SurfaceView implements SurfaceHolder.Callback {

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

    private final long startNanos = System.nanoTime();

    public SkyView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        getHolder().addCallback(this);
    }

    public void setWeather(float w) { this.weather = w; }

    public void setTint(int[] rampArgb) { this.tintRamp = rampArgb; }

    public void setDesaturation(float amount) { this.desaturation = amount; }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startThreadIfNeeded();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        int bh = Math.round(BUF_W * (float) h / Math.max(1, w));
        bh = Math.max(96, Math.min(320, bh));
        if (renderer == null || bh != bufH) {
            bufH = bh;
            buf = new int[BUF_W * bufH];
            bitmap = Bitmap.createBitmap(BUF_W, bufH, Bitmap.Config.ARGB_8888);
            renderer = new SkyRenderer(BUF_W, bufH);
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopThread();
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

        float hour = decimalHour();
        float seconds = (System.nanoTime() - startNanos) / 1_000_000_000f;
        r.render(buf, hour, weather, 0.62f, seconds);
        bmp.setPixels(buf, 0, BUF_W, 0, 0, BUF_W, bufH);

        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            dst.set(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bmp, null, dst, paint);
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas); } catch (IllegalArgumentException ignored) { /* surface gone */ }
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
