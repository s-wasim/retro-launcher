package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.JulianFields;

/**
 * Sunrise and sunset from latitude, longitude and date, offline — the
 * fallback for when {@code OpenMeteoWeather}'s network fetch does not answer
 * or its {@code daily} block cannot be parsed. Pure math, no I/O, no Android
 * type. Implements the standard NOAA-derived sunrise equation (see
 * Wikipedia, "Sunrise equation"), correct to roughly a minute — far finer
 * than the 14-keyframe sky gradient this feeds can express.
 */
public final class SolarMath {

    private SolarMath() {}

    /**
     * @return today's sunrise, today's sunset and tomorrow's sunrise as
     *         decimal local hours in {@code zone}, or {@code null} if the
     *         sun does not rise or set that day at this latitude (polar day
     *         or polar night) on either day.
     */
    public static SolarTimes sunTimes(float latitude, float longitude, LocalDate date, ZoneId zone) {
        DayResult today = compute(latitude, longitude, date, zone);
        if (today == null) return null;
        DayResult tomorrow = compute(latitude, longitude, date.plusDays(1), zone);
        if (tomorrow == null) return null;
        return new SolarTimes(today.sunrise, today.sunset, tomorrow.sunrise, date);
    }

    private static final class DayResult {
        float sunrise, sunset;
    }

    private static DayResult compute(float latitude, float longitude, LocalDate date, ZoneId zone) {
        long julianDay = date.getLong(JulianFields.JULIAN_DAY);
        double n = julianDay - 2451545.0 + 0.0008;
        double meanSolarNoon = n - longitude / 360.0;
        double solarMeanAnomaly = norm360(357.5291 + 0.98560028 * meanSolarNoon);
        double eqCenter = 1.9148 * sinDeg(solarMeanAnomaly)
                         + 0.0200 * sinDeg(2 * solarMeanAnomaly)
                         + 0.0003 * sinDeg(3 * solarMeanAnomaly);
        double eclipticLongitude = norm360(solarMeanAnomaly + 102.9372 + eqCenter + 180.0);
        double solarTransit = meanSolarNoon
                + 0.0053 * sinDeg(solarMeanAnomaly)
                - 0.0069 * sinDeg(2 * eclipticLongitude);
        double declination = Math.asin(sinDeg(eclipticLongitude) * sinDeg(23.4397));

        double latRad = Math.toRadians(latitude);
        double cosHourAngle = (sinDeg(-0.833) - Math.sin(latRad) * Math.sin(declination))
                             / (Math.cos(latRad) * Math.cos(declination));
        if (Double.isNaN(cosHourAngle) || cosHourAngle < -1.0 || cosHourAngle > 1.0) {
            return null; // polar day (sun never sets) or polar night (never rises)
        }
        double hourAngle = Math.toDegrees(Math.acos(cosHourAngle));

        double sunriseJulian = solarTransit - hourAngle / 360.0;
        double sunsetJulian  = solarTransit + hourAngle / 360.0;

        Float sunriseHour = toLocalHour(sunriseJulian, date, zone);
        Float sunsetHour  = toLocalHour(sunsetJulian, date, zone);
        if (sunriseHour == null || sunsetHour == null) return null;

        DayResult r = new DayResult();
        r.sunrise = sunriseHour;
        r.sunset = sunsetHour;
        return r;
    }

    /**
     * Converts a fractional Julian Day (UT) into a decimal local hour on
     * {@code date} in {@code zone}. {@code julianDayFraction} may fall
     * slightly before or after {@code date}'s own UTC midnight—midnight
     * window; the result is still normalized into {@code [0, 24)}.
     */
    private static Float toLocalHour(double julianDayFraction, LocalDate date, ZoneId zone) {
        double jdMidnightUtc = date.getLong(JulianFields.JULIAN_DAY) - 0.5;
        double utDaysSinceMidnight = julianDayFraction - jdMidnightUtc;
        double utHour = utDaysSinceMidnight * 24.0;

        LocalDateTime approxLocalMidnight = date.atStartOfDay();
        ZoneOffset offset = zone.getRules().getOffset(approxLocalMidnight);
        double localHour = utHour + offset.getTotalSeconds() / 3600.0;

        double h = localHour % 24.0;
        if (h < 0) h += 24.0;
        if (Double.isNaN(h) || Double.isInfinite(h)) return null;
        return (float) h;
    }

    private static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double norm360(double degrees) {
        double v = degrees % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
