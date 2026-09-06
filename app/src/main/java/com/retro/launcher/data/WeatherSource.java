package com.retro.launcher.data;

import com.retro.launcher.core.WeatherFetch;

/**
 * Where a real weather reading — and the day's solar times, riding along on
 * the same request — comes from. The seam spec §3.6 reserved at Tier 1 so
 * the network implementation could land without touching a caller.
 */
public interface WeatherSource {

    /**
     * Fetches one reading. Blocking — callers run it off the main thread.
     *
     * @return the reading and solar times, or null for any failure at all:
     *         no network, a non-200 reply, a timeout, a body we cannot
     *         parse. {@code WeatherFetch.solarTimes} may itself be null even
     *         on success — failure there is independently normal. Failure is
     *         never an exception.
     */
    WeatherFetch fetch(double latitude, double longitude);
}
