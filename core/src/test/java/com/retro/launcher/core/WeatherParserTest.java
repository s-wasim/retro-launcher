package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherParserTest {

    /** A verbatim Open-Meteo reply to
     *  /v1/forecast?latitude=52.52&longitude=13.419&current_weather=true */
    private static final String RECORDED =
            "{\"latitude\":52.52,\"longitude\":13.419,\"generationtime_ms\":0.2378225326538086,"
            + "\"utc_offset_seconds\":0,\"timezone\":\"GMT\",\"timezone_abbreviation\":\"GMT\","
            + "\"elevation\":38.0,\"current_weather\":{\"temperature\":13.4,\"windspeed\":10.3,"
            + "\"winddirection\":204,\"weathercode\":3,\"is_day\":1,\"time\":\"2026-08-28T20:00\"}}";

    // ---- the recorded payload ------------------------------------------

    @Test public void readsTemperatureFromTheRecordedPayload() {
        assertEquals(13, WeatherParser.parse(RECORDED).tempC);
    }

    @Test public void readsConditionFromTheRecordedPayload() {
        // WMO 3 is overcast.
        assertEquals("OVERCAST", WeatherParser.parse(RECORDED).label);
    }

    @Test public void recordedPayloadYieldsAnInRangeSkyScalar() {
        float w = WeatherParser.parse(RECORDED).w;
        assertTrue("w out of range: " + w, w >= 0f && w <= 1f);
    }

    // ---- code to condition ---------------------------------------------

    @Test public void clearSkyCodeIsClear() {
        assertEquals("CLEAR", parseCode(0).label);
    }

    @Test public void partlyCloudyCodeIsPartlyCloudy() {
        assertEquals("PARTLY CLOUDY", parseCode(2).label);
    }

    @Test public void fogCodeReadsAsHaze() {
        assertEquals("HAZY", parseCode(45).label);
    }

    @Test public void heavyRainCodeIsADownpour() {
        assertEquals("DOWNPOUR", parseCode(65).label);
    }

    @Test public void thunderstormCodeIsAThunderstorm() {
        assertEquals("THUNDERSTORM", parseCode(95).label);
    }

    @Test public void snowCodesUseTheSnowLabels() {
        assertEquals("SNOW", parseCode(73).label);
    }

    @Test public void freezingRainCountsAsSnow() {
        assertEquals("LIGHT SNOW", parseCode(66).label);
    }

    @Test public void wetterCodesProduceALargerSkyScalar() {
        assertTrue(parseCode(95).w > parseCode(61).w);
        assertTrue(parseCode(61).w > parseCode(2).w);
        assertTrue(parseCode(2).w > parseCode(0).w);
    }

    // ---- temperature handling ------------------------------------------

    @Test public void temperatureRoundsToTheNearestDegree() {
        assertEquals(14, parseTemp("13.6").tempC);
    }

    @Test public void negativeTemperaturesKeepTheirSign() {
        assertEquals(-7, parseTemp("-6.8").tempC);
    }

    @Test public void temperatureMayArriveWithoutADecimalPoint() {
        assertEquals(21, parseTemp("21").tempC);
    }

    // ---- shapes that must yield "no update" -----------------------------

    @Test public void nullInputYieldsNoUpdate() {
        assertNull(WeatherParser.parse(null));
    }

    @Test public void emptyInputYieldsNoUpdate() {
        assertNull(WeatherParser.parse(""));
    }

    @Test public void anObjectWithoutCurrentWeatherYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"latitude\":52.5,\"longitude\":13.4}"));
    }

    @Test public void anEmptyCurrentWeatherYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current_weather\":{}}"));
    }

    @Test public void missingWeathercodeYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current_weather\":{\"temperature\":13.4}}"));
    }

    @Test public void missingTemperatureYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current_weather\":{\"weathercode\":3}}"));
    }

    @Test public void truncatedJsonYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current_weather\":{\"temperature\":13."));
    }

    @Test public void anHtmlErrorPageYieldsNoUpdate() {
        assertNull(WeatherParser.parse("<html><body>502 Bad Gateway</body></html>"));
    }

    @Test public void anApiErrorObjectYieldsNoUpdate() {
        assertNull(WeatherParser.parse(
                "{\"error\":true,\"reason\":\"Latitude must be in range of -90 to 90\"}"));
    }

    @Test public void aNonNumericTemperatureYieldsNoUpdate() {
        assertNull(WeatherParser.parse(
                "{\"current_weather\":{\"temperature\":\"warm\",\"weathercode\":3}}"));
    }

    @Test public void anUnrecognisedWeatherCodeYieldsNoUpdate() {
        // The WMO code set is closed. A code outside it means our table is
        // wrong, and keeping the last good reading beats inventing a sky.
        assertNull(parseCodeRaw(42));
    }

    // ---- scanner robustness ---------------------------------------------

    @Test public void currentWeatherUnitsDoesNotMasqueradeAsCurrentWeather() {
        // The newer /v1/forecast?current= form emits a units object whose key
        // has "current_weather" as a prefix. Reading it instead would give
        // back the string "°C" as a temperature.
        String json = "{\"current_weather_units\":{\"temperature\":\"°C\",\"weathercode\":\"wmo\"},"
                + "\"current_weather\":{\"temperature\":9.1,\"weathercode\":0}}";
        assertEquals(9, WeatherParser.parse(json).tempC);
    }

    @Test public void keyOrderInsideCurrentWeatherDoesNotMatter() {
        assertEquals(9, WeatherParser.parse(
                "{\"current_weather\":{\"weathercode\":0,\"temperature\":9.1}}").tempC);
    }

    @Test public void unknownKeysInsideCurrentWeatherAreIgnored() {
        assertEquals(9, WeatherParser.parse("{\"current_weather\":{\"interval\":900,"
                + "\"temperature\":9.1,\"weathercode\":0,\"future_field\":{\"a\":1}}}").tempC);
    }

    @Test public void whitespaceAroundSeparatorsIsTolerated() {
        assertEquals(9, WeatherParser.parse(
                "{ \"current_weather\" : { \"temperature\" : 9.1 , \"weathercode\" : 0 } }").tempC);
    }

    @Test public void bracesInsideStringValuesDoNotEndTheObject() {
        assertEquals(9, WeatherParser.parse("{\"current_weather\":{\"time\":\"}{\","
                + "\"temperature\":9.1,\"weathercode\":0}}").tempC);
    }

    // ---- helpers ---------------------------------------------------------

    private static Weather parseCode(int code) {
        Weather w = parseCodeRaw(code);
        assertNotNull("expected code " + code + " to be recognised", w);
        return w;
    }

    private static Weather parseCodeRaw(int code) {
        return WeatherParser.parse(
                "{\"current_weather\":{\"temperature\":5.0,\"weathercode\":" + code + "}}");
    }

    private static Weather parseTemp(String temp) {
        Weather w = WeatherParser.parse(
                "{\"current_weather\":{\"temperature\":" + temp + ",\"weathercode\":0}}");
        assertNotNull(w);
        return w;
    }
}
