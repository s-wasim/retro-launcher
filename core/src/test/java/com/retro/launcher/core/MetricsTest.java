package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MetricsTest {

    @Test public void cqwIsOnePercentOfScreenWidth() {
        Metrics m = new Metrics(1080f, 3f, 3f);
        assertEquals(43.2f, m.cqw(4f), 0.001f);   // dock/widget inset
        assertEquals(101.52f, m.cqw(9.4f), 0.001f); // clock digits
    }

    @Test public void cqwScalesWithScreenWidth() {
        assertEquals(14.4f, new Metrics(360f, 1f, 1f).cqw(4f), 0.001f);
        assertEquals(19.2f, new Metrics(480f, 1f, 1f).cqw(4f), 0.001f);
    }

    @Test public void dpMultipliesByDensity() {
        assertEquals(144f, new Metrics(1080f, 3f, 3f).dp(48f), 0.001f);
    }

    @Test public void textPxHonoursTheLegibilityFloor() {
        // 2.2cqw on a 360px-wide screen is 7.92px — below a 10sp floor.
        Metrics m = new Metrics(360f, 1f, 1f);
        assertEquals(10f, m.textPx(2.2f, 10f), 0.001f);
    }

    @Test public void textPxUsesCqwWhenAboveTheFloor() {
        Metrics m = new Metrics(1080f, 3f, 3f);
        assertEquals(101.52f, m.textPx(9.4f, 10f), 0.001f);
    }
}
