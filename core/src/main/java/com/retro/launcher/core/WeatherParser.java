package com.retro.launcher.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Open-Meteo's modern {@code current=} reply into a {@link Weather}.
 *
 * <h3>Why this parses JSON by hand</h3>
 * This class lives in {@code :core}, and {@code :core} having no Android
 * dependency is exactly what lets it be unit-tested on the JVM. The reader
 * below is deliberately small and deliberately narrow — it finds one named
 * object and a handful of named numbers inside it, and refuses everything
 * else.
 *
 * <h3>Failure is normal and silent</h3>
 * Every malformed shape — a truncated body, an HTML error page, an API error
 * object, a missing required field, a field of the wrong type — returns
 * null, meaning "no update". A missing *optional* channel (cloud cover,
 * precipitation) instead falls back to the value implied by
 * {@code weather_code}, reproducing pre-V9 behaviour for that one channel
 * rather than failing the whole reading. This class never throws.
 */
public final class WeatherParser {

    private WeatherParser() {}

    private static final String OBJECT_KEY = "\"current\"";

    /** mm of the preceding hour that saturates the visual {@code precip}
     *  intensity to 1.0 — 4mm/h is WMO's "heavy rain" threshold. */
    private static final float PRECIP_SATURATION_MM = 4f;

    /**
     * @param json a full Open-Meteo response body, or null
     * @return the reading, or null if the body could not be read with
     *         confidence — including a WMO code outside the known set
     */
    public static Weather parse(String json) {
        if (json == null) return null;

        String body = objectFor(json, OBJECT_KEY);
        if (body == null) return null;

        Double temp = number(body, "\"temperature_2m\"");
        Double code = number(body, "\"weather_code\"");
        if (temp == null || code == null) return null;

        Condition c = Condition.forWmoCode((int) Math.round(code));
        if (c == null) return null;

        Double cloudPct = number(body, "\"cloud_cover\"");
        Double precipMm = number(body, "\"precipitation\"");
        Double precipProb = number(body, "\"precipitation_probability\"");

        float cloudCover = cloudPct != null
                ? SkyRenderer.clamp01(cloudPct.floatValue() / 100f)
                : SkyRenderer.smooth(0.10f, 0.66f, c.w);
        float precip = precipMm != null
                ? SkyRenderer.clamp01(precipMm.floatValue() / PRECIP_SATURATION_MM)
                : SkyRenderer.smooth(0.62f, 0.98f, c.w);
        int precipProbability = precipProb != null ? Math.round(precipProb.floatValue()) : 0;

        boolean thunder = c.thunder;
        Precip type = precip > 0f ? (c.snow ? Precip.SNOW : Precip.RAIN) : Precip.NONE;

        // The label comes from the WMO code's own canonical scalar, not the
        // channel-derived w: reproducing today's per-code label exactly is
        // the whole point of the WMO fallback (Global Constraints), and a
        // round-trip through the fallback-synthesized cloudCover/precip is
        // lossy enough at the low end to misalign label-band boundaries.
        return new Weather((int) Math.round(temp), SyntheticWeather.label(c.w, c.snow),
                cloudCover, precip, type, thunder, precipProbability);
    }

    private static final String DAILY_KEY = "\"daily\"";

    /**
     * @param json  a full Open-Meteo response body with {@code &daily=
     *              sunrise,sunset&forecast_days=2} appended to the request,
     *              or null
     * @param today the local date the first element of the {@code daily}
     *              arrays is expected to describe
     * @return today's sunrise, sunset and tomorrow's sunrise, or null if the
     *         block is absent or cannot be read with confidence
     */
    public static SolarTimes parseSolarTimes(String json, LocalDate today) {
        if (json == null) return null;

        String body = objectFor(json, DAILY_KEY);
        if (body == null) return null;

        List<String> sunrises = stringArray(body, "\"sunrise\"");
        List<String> sunsets = stringArray(body, "\"sunset\"");
        if (sunrises == null || sunsets == null) return null;
        if (sunrises.size() < 2 || sunsets.size() < 1) return null;

        Float sunriseHour = hourOfDay(sunrises.get(0));
        Float sunsetHour = hourOfDay(sunsets.get(0));
        Float tomorrowSunriseHour = hourOfDay(sunrises.get(1));
        if (sunriseHour == null || sunsetHour == null || tomorrowSunriseHour == null) return null;

        return new SolarTimes(sunriseHour, sunsetHour, tomorrowSunriseHour, today);
    }

    private static List<String> stringArray(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(body, at + key.length());
        if (i >= body.length() || body.charAt(i) != ':') return null;
        i = skipSpace(body, i + 1);
        if (i >= body.length() || body.charAt(i) != '[') return null;
        i++;

        List<String> out = new ArrayList<>();
        i = skipSpace(body, i);
        if (i < body.length() && body.charAt(i) == ']') return out;

        while (i < body.length()) {
            if (body.charAt(i) != '"') return null;
            int start = ++i;
            while (i < body.length() && body.charAt(i) != '"') i++;
            if (i >= body.length()) return null;
            out.add(body.substring(start, i));
            i = skipSpace(body, i + 1);
            if (i >= body.length()) return null;
            if (body.charAt(i) == ',') { i = skipSpace(body, i + 1); continue; }
            if (body.charAt(i) == ']') return out;
            return null;
        }
        return null;
    }

    private static Float hourOfDay(String isoLocalDateTime) {
        try {
            LocalDateTime dt = LocalDateTime.parse(isoLocalDateTime);
            return dt.getHour() + dt.getMinute() / 60f;
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private static String objectFor(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(json, at + key.length());
        if (i >= json.length() || json.charAt(i) != ':') return null;
        i = skipSpace(json, i + 1);
        if (i >= json.length() || json.charAt(i) != '{') return null;

        int depth = 0;
        boolean inString = false, escaped = false;
        for (int j = i; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (escaped)          { escaped = false; continue; }
            if (ch == '\\' && inString) { escaped = true; continue; }
            if (ch == '"')        { inString = !inString; continue; }
            if (inString)         continue;
            if (ch == '{')        depth++;
            else if (ch == '}' && --depth == 0) return json.substring(i + 1, j);
        }
        return null;
    }

    private static Double number(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(body, at + key.length());
        if (i >= body.length() || body.charAt(i) != ':') return null;
        i = skipSpace(body, i + 1);

        int start = i;
        while (i < body.length() && isNumeric(body.charAt(i))) i++;
        if (i == start) return null;

        try {
            return Double.valueOf(body.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNumeric(char c) {
        return (c >= '0' && c <= '9')
                || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /**
     * One WMO 4677 present-weather code's *implied* effect on the sky, used
     * only as a per-channel fallback when Open-Meteo's own channel is
     * missing from the response, and always for {@code thunder} (the API has
     * no boolean field for it — codes 95/96/99 are the only source of truth).
     */
    private static final class Condition {
        final float w;
        final boolean snow;
        final boolean thunder;

        Condition(float w, boolean snow, boolean thunder) {
            this.w = w;
            this.snow = snow;
            this.thunder = thunder;
        }

        static Condition forWmoCode(int code) {
            switch (code) {
                case 0:  return new Condition(0.02f, false, false);
                case 1:  return new Condition(0.22f, false, false);
                case 2:  return new Condition(0.36f, false, false);
                case 3:  return new Condition(0.58f, false, false);

                case 45: case 48:
                    return new Condition(0.12f, false, false);

                case 51: case 53:
                    return new Condition(0.70f, false, false);
                case 55:
                    return new Condition(0.82f, false, false);
                case 56: case 57:
                    return new Condition(0.70f, true, false);

                case 61: return new Condition(0.70f, false, false);
                case 63: return new Condition(0.82f, false, false);
                case 65: return new Condition(0.91f, false, false);
                case 66: return new Condition(0.70f, true, false);
                case 67: return new Condition(0.82f, true, false);

                case 71: return new Condition(0.70f, true, false);
                case 73: return new Condition(0.82f, true, false);
                case 75: return new Condition(0.91f, true, false);
                case 77: return new Condition(0.70f, true, false);

                case 80: return new Condition(0.70f, false, false);
                case 81: return new Condition(0.82f, false, false);
                case 82: return new Condition(0.91f, false, false);
                case 85: return new Condition(0.70f, true, false);
                case 86: return new Condition(0.91f, true, false);

                case 95: case 96: case 99:
                    return new Condition(0.97f, false, true);

                default: return null;
            }
        }
    }
}
