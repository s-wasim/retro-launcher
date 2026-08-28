package com.retro.launcher.data;

import com.retro.launcher.core.SyntheticWeather;
import com.retro.launcher.core.Weather;

/**
 * Tier 1's thin wrapper around {@link SyntheticWeather} — the seam Tier 5's
 * OpenMeteoWeather slots into without touching any caller. See spec §3.6.
 */
public final class WeatherRepository {

    public Weather current(float hour) {
        // No real signal yet: clear sky, driven purely by the clock.
        return SyntheticWeather.at(hour, 0f, false);
    }
}
