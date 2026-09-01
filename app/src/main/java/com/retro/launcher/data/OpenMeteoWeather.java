package com.retro.launcher.data;

import android.util.Log;

import com.retro.launcher.core.Weather;
import com.retro.launcher.core.WeatherParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * One HTTPS GET to api.open-meteo.com — no key, no account, no SDK.
 *
 * Deliberately minimal: one attempt, short timeouts, a bounded read, and null
 * for every failure. {@link WeatherRepository} owns the decision of *when* to
 * call this; the only policy here is "give up quickly".
 */
public final class OpenMeteoWeather implements WeatherSource {

    private static final String TAG = "Weather";
    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";

    private static final int TIMEOUT_MS = 5_000;

    /** The real body is ~250 bytes. Anything wildly larger is not our JSON,
     *  and reading it unbounded would be a memory hazard on a hostile network. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    @Override public Weather fetch(double latitude, double longitude) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT
                    + "?latitude=" + coord(latitude)
                    + "&longitude=" + coord(longitude)
                    + "&current_weather=true");

            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "open-meteo returned HTTP " + code);
                return null;
            }
            return WeatherParser.parse(read(conn.getInputStream()));

        } catch (Exception e) {
            // IOException, SSL failures, a malformed URL, a SecurityException
            // from a restricted profile — all the same outcome: no update.
            Log.d(TAG, "fetch failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Three decimal places — about 110m.
     *
     * That is far finer than weather varies and coarser than the fix we were
     * given, so it trims precision we have no use for before it leaves the
     * device. Locale.US because a device set to a comma-decimal locale would
     * otherwise send "52,52" and get a 400 back.
     */
    private static String coord(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                Log.d(TAG, "response exceeded " + MAX_BODY_BYTES + " bytes; discarding");
                return null;
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
