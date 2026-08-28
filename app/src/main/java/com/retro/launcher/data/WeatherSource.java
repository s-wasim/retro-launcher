package com.retro.launcher.data;

import com.retro.launcher.core.Weather;

/**
 * Where a real weather reading comes from. The seam spec §3.6 reserved at
 * Tier 1 so the network implementation could land without touching a caller.
 */
public interface WeatherSource {

    /**
     * Fetches one reading. Blocking — callers run it off the main thread.
     *
     * @return the reading, or null for any failure at all: no network, a
     *         non-200 reply, a timeout, a body we cannot parse. Failure is
     *         normal here and is never an exception.
     */
    Weather fetch(double latitude, double longitude);
}
