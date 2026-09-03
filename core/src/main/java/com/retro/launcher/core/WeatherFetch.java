package com.retro.launcher.core;

/**
 * The two things one Open-Meteo request can yield: the current reading, and
 * — since {@code &daily=sunrise,sunset} rides along on the same GET — the
 * day's solar times. {@code solarTimes} is null whenever the {@code daily}
 * block was absent or unparseable; {@code weather} follows the same
 * null-on-failure contract {@link WeatherParser#parse} always had.
 */
public final class WeatherFetch {

	public final Weather weather;
	public final SolarTimes solarTimes;

	public WeatherFetch(Weather weather, SolarTimes solarTimes) {
		this.weather = weather;
		this.solarTimes = solarTimes;
	}
}
