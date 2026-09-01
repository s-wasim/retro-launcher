package com.retro.launcher.core;

/**
 * Turns Open-Meteo's {@code current_weather} reply into a {@link Weather}.
 *
 * <h3>Why this parses JSON by hand</h3>
 * The design spec calls for {@code android.util.JsonReader} here. That cannot
 * hold: this class lives in {@code :core}, and {@code :core} having no Android
 * dependency is exactly what lets it be unit-tested on the JVM. Adding a JSON
 * library for two numbers is worse. So the reader below is deliberately small
 * and deliberately narrow — it finds one named object and two named numbers
 * inside it, and refuses everything else.
 *
 * <h3>Failure is normal and silent</h3>
 * Per spec §3.6, every malformed shape — a truncated body, an HTML error page,
 * an API error object, a missing field, a field of the wrong type — returns
 * null, meaning "no update". The caller keeps its last good reading. This
 * class never throws.
 */
public final class WeatherParser {

    private WeatherParser() {}

    private static final String OBJECT_KEY = "\"current_weather\"";

    /**
     * @param json a full Open-Meteo response body, or null
     * @return the reading, or null if the body could not be read with
     *         confidence — including a WMO code outside the known set
     */
    public static Weather parse(String json) {
        if (json == null) return null;

        String body = objectFor(json, OBJECT_KEY);
        if (body == null) return null;

        Double temp = number(body, "\"temperature\"");
        Double code = number(body, "\"weathercode\"");
        if (temp == null || code == null) return null;

        Condition c = Condition.forWmoCode((int) Math.round(code));
        if (c == null) return null;

        return new Weather((int) Math.round(temp),
                SyntheticWeather.label(c.w, c.snow), c.w);
    }

    /**
     * The text between the braces of the object at {@code key}, or null.
     *
     * The key is matched with its quotes attached, so {@code
     * "current_weather_units"} cannot be mistaken for {@code
     * "current_weather"} — reading that one would hand back "°C" as a
     * temperature.
     */
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
        return null;   // never closed — a truncated body
    }

    /**
     * The numeric value at {@code key} within one object body, or null if the
     * key is absent or its value is not a bare JSON number.
     */
    private static Double number(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return null;

        int i = skipSpace(body, at + key.length());
        if (i >= body.length() || body.charAt(i) != ':') return null;
        i = skipSpace(body, i + 1);

        int start = i;
        while (i < body.length() && isNumeric(body.charAt(i))) i++;
        if (i == start) return null;   // a string, an object, null, true...

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
     * One WMO 4677 present-weather code's effect on the sky.
     *
     * {@code w} is the same 0-1 scalar {@link SyntheticWeather} uses, so each
     * value is chosen to land inside the band whose label matches the code's
     * meaning — see {@link SyntheticWeather#label}.
     */
    private static final class Condition {
        final float w;
        final boolean snow;

        Condition(float w, boolean snow) { this.w = w; this.snow = snow; }

        static Condition forWmoCode(int code) {
            switch (code) {
                case 0:  return new Condition(0.02f, false);  // clear sky
                case 1:  return new Condition(0.22f, false);  // mainly clear
                case 2:  return new Condition(0.36f, false);  // partly cloudy
                case 3:  return new Condition(0.58f, false);  // overcast

                case 45: case 48:                             // fog, rime fog
                    return new Condition(0.12f, false);

                case 51: case 53:                             // drizzle
                    return new Condition(0.70f, false);
                case 55:
                    return new Condition(0.82f, false);
                case 56: case 57:                             // freezing drizzle
                    return new Condition(0.70f, true);

                case 61: return new Condition(0.70f, false);  // slight rain
                case 63: return new Condition(0.82f, false);  // moderate rain
                case 65: return new Condition(0.91f, false);  // heavy rain
                case 66: return new Condition(0.70f, true);   // freezing rain
                case 67: return new Condition(0.82f, true);

                case 71: return new Condition(0.70f, true);   // slight snow
                case 73: return new Condition(0.82f, true);   // moderate snow
                case 75: return new Condition(0.91f, true);   // heavy snow
                case 77: return new Condition(0.70f, true);   // snow grains

                case 80: return new Condition(0.70f, false);  // rain showers
                case 81: return new Condition(0.82f, false);
                case 82: return new Condition(0.91f, false);
                case 85: return new Condition(0.70f, true);   // snow showers
                case 86: return new Condition(0.91f, true);

                case 95: case 96: case 99:                    // thunderstorm
                    return new Condition(0.97f, false);

                default: return null;
            }
        }
    }
}
