package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherParserTest {

    /** A modern Open-Meteo reply to
     *  /v1/forecast?...&current=temperature_2m,weather_code,cloud_cover,precipitation,precipitation_probability */
    private static final String RECORDED =
            "{\"latitude\":52.52,\"longitude\":13.419,\"generationtime_ms\":0.23,"
            + "\"utc_offset_seconds\":0,\"timezone\":\"GMT\",\"timezone_abbreviation\":\"GMT\","
            + "\"elevation\":38.0,\"current\":{\"time\":\"2026-08-28T20:00\",\"temperature_2m\":13.4,"
            + "\"weather_code\":3,\"cloud_cover\":88,\"precipitation\":0.0,\"precipitation_probability\":10}}";

    // ---- the recorded payload ------------------------------------------

    @Test public void readsTemperatureFromTheRecordedPayload() {
        assertEquals(13, WeatherParser.parse(RECORDED).tempC);
    }

    @Test public void readsConditionFromTheRecordedPayload() {
        assertEquals("OVERCAST", WeatherParser.parse(RECORDED).label);
    }

    @Test public void readsCloudCoverDirectlyFromTheChannel() {
        assertEquals(0.88f, WeatherParser.parse(RECORDED).cloudCover, 0.001f);
    }

    @Test public void readsPrecipProbabilityDirectly() {
        assertEquals(10, WeatherParser.parse(RECORDED).precipProbability);
    }

    @Test public void recordedPayloadYieldsAnInRangeSkyScalar() {
        float w = WeatherParser.parse(RECORDED).w;
        assertTrue("w out of range: " + w, w >= 0f && w <= 1f);
    }

    // ---- the four channels are independent -------------------------------

    @Test public void lightDrizzleDrawsFewCloudsAndSparseRain() {
        String json = "{\"current\":{\"temperature_2m\":12.0,\"weather_code\":51,"
                + "\"cloud_cover\":20,\"precipitation\":0.2,\"precipitation_probability\":60}}";
        Weather w = WeatherParser.parse(json);
        assertEquals(0.20f, w.cloudCover, 0.001f);
        assertEquals(Precip.RAIN, w.type);
        assertTrue("expected a light precip intensity", w.precip > 0f && w.precip < 0.2f);
        assertFalse(w.thunder);
    }

    @Test public void dryThunderstormHasThunderAndZeroPrecip() {
        // Cloud is high, precipitation is explicitly zero — a real "storm
        // building, no rain yet" reading, which the old coupled-scalar
        // system could never represent.
        String json = "{\"current\":{\"temperature_2m\":22.0,\"weather_code\":95,"
                + "\"cloud_cover\":95,\"precipitation\":0.0,\"precipitation_probability\":30}}";
        Weather w = WeatherParser.parse(json);
        assertTrue(w.thunder);
        assertEquals(0f, w.precip, 0.0001f);
        assertEquals(Precip.NONE, w.type);
        assertEquals(1.0f, w.w, 0.001f);
    }

    @Test public void snowCodeWithPrecipitationYieldsSnowType() {
        String json = "{\"current\":{\"temperature_2m\":-3.0,\"weather_code\":73,"
                + "\"cloud_cover\":80,\"precipitation\":1.5,\"precipitation_probability\":90}}";
        Weather w = WeatherParser.parse(json);
        assertEquals(Precip.SNOW, w.type);
        assertTrue(w.precip > 0f);
    }

    @Test public void heavyRainSaturatesPrecipAtFourMillimetresAnHour() {
        String json = "{\"current\":{\"temperature_2m\":15.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation\":4.0,\"precipitation_probability\":100}}";
        assertEquals(1.0f, WeatherParser.parse(json).precip, 0.001f);
    }

    @Test public void precipitationAboveTheSaturationPointClampsToOne() {
        String json = "{\"current\":{\"temperature_2m\":15.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation\":40.0,\"precipitation_probability\":100}}";
        assertEquals(1.0f, WeatherParser.parse(json).precip, 0.001f);
    }

    @Test public void missingCloudCoverFallsBackToTheCodeImpliedValue() {
        // weather_code 3 (overcast) implies a high cloud cover in the old
        // per-code table, applied only because cloud_cover is absent here.
        String json = "{\"current\":{\"temperature_2m\":13.0,\"weather_code\":3,"
                + "\"precipitation\":0.0,\"precipitation_probability\":0}}";
        Weather w = WeatherParser.parse(json);
        assertTrue("expected a substantial implied cloud cover for overcast", w.cloudCover > 0.3f);
    }

    @Test public void missingPrecipitationFallsBackToTheCodeImpliedValue() {
        String json = "{\"current\":{\"temperature_2m\":13.0,\"weather_code\":65,"
                + "\"cloud_cover\":100,\"precipitation_probability\":100}}";
        Weather w = WeatherParser.parse(json);
        assertTrue("expected a substantial implied precip for heavy rain", w.precip > 0.3f);
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

    @Test public void anObjectWithoutCurrentYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"latitude\":52.5,\"longitude\":13.4}"));
    }

    @Test public void anEmptyCurrentYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{}}"));
    }

    @Test public void missingWeatherCodeYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"temperature_2m\":13.4}}"));
    }

    @Test public void missingTemperatureYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"weather_code\":3}}"));
    }

    @Test public void truncatedJsonYieldsNoUpdate() {
        assertNull(WeatherParser.parse("{\"current\":{\"temperature_2m\":13."));
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
                "{\"current\":{\"temperature_2m\":\"warm\",\"weather_code\":3}}"));
    }

    @Test public void anUnrecognisedWeatherCodeYieldsNoUpdate() {
        assertNull(parseCodeRaw(42));
    }

    // ---- daily sunrise/sunset -------------------------------------------

    private static final String RECORDED_WITH_DAILY =
            "{\"latitude\":52.52,\"longitude\":13.419,\"timezone\":\"Europe/Berlin\","
            + "\"current\":{\"temperature_2m\":13.4,\"weather_code\":3},"
            + "\"daily\":{\"time\":[\"2026-08-28\",\"2026-08-29\"],"
            + "\"sunrise\":[\"2026-08-28T06:12\",\"2026-08-29T06:14\"],"
            + "\"sunset\":[\"2026-08-28T20:31\",\"2026-08-29T20:29\"]}}";

    @Test public void parsesSunriseSunsetAndTomorrowSunriseFromTheDailyBlock() {
        SolarTimes t = WeatherParser.parseSolarTimes(RECORDED_WITH_DAILY, java.time.LocalDate.of(2026, 8, 28));
        assertNotNull(t);
        assertEquals(6f + 12f / 60f, t.sunriseHour, 0.001f);
        assertEquals(20f + 31f / 60f, t.sunsetHour, 0.001f);
        assertEquals(6f + 14f / 60f, t.tomorrowSunriseHour, 0.001f);
        assertEquals(java.time.LocalDate.of(2026, 8, 28), t.date);
    }

    @Test public void absentDailyBlockYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(
                "{\"current\":{\"temperature_2m\":13.4,\"weather_code\":3}}",
                java.time.LocalDate.of(2026, 8, 28)));
    }

    @Test public void malformedDailyBlockYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(
                "{\"daily\":{\"sunrise\":[\"not-a-time\"],\"sunset\":[]}}",
                java.time.LocalDate.of(2026, 8, 28)));
    }

    @Test public void nullJsonYieldsNullSolarTimes() {
        assertNull(WeatherParser.parseSolarTimes(null, java.time.LocalDate.of(2026, 8, 28)));
    }

    // ---- scanner robustness ---------------------------------------------

    @Test public void currentUnitsDoesNotMasqueradeAsCurrent() {
        // The modern API emits a units object whose key has "current" as a
        // prefix. Reading it instead would give back "°C" as a temperature.
        String json = "{\"current_units\":{\"temperature_2m\":\"°C\",\"weather_code\":\"wmo code\"},"
                + "\"current\":{\"temperature_2m\":9.1,\"weather_code\":0}}";
        assertEquals(9, WeatherParser.parse(json).tempC);
    }

    @Test public void keyOrderInsideCurrentDoesNotMatter() {
        assertEquals(9, WeatherParser.parse(
                "{\"current\":{\"weather_code\":0,\"temperature_2m\":9.1}}").tempC);
    }

    @Test public void unknownKeysInsideCurrentAreIgnored() {
        assertEquals(9, WeatherParser.parse("{\"current\":{\"interval\":900,"
                + "\"temperature_2m\":9.1,\"weather_code\":0,\"future_field\":{\"a\":1}}}").tempC);
    }

    @Test public void whitespaceAroundSeparatorsIsTolerated() {
        assertEquals(9, WeatherParser.parse(
                "{ \"current\" : { \"temperature_2m\" : 9.1 , \"weather_code\" : 0 } }").tempC);
    }

    @Test public void bracesInsideStringValuesDoNotEndTheObject() {
        assertEquals(9, WeatherParser.parse("{\"current\":{\"time\":\"}{\","
                + "\"temperature_2m\":9.1,\"weather_code\":0}}").tempC);
    }

    // ---- helpers ---------------------------------------------------------

    private static Weather parseCode(int code) {
        Weather w = parseCodeRaw(code);
        assertNotNull("expected code " + code + " to be recognised", w);
        return w;
    }

    private static Weather parseCodeRaw(int code) {
        return WeatherParser.parse(
                "{\"current\":{\"temperature_2m\":5.0,\"weather_code\":" + code + "}}");
    }

    private static Weather parseTemp(String temp) {
        Weather w = WeatherParser.parse(
                "{\"current\":{\"temperature_2m\":" + temp + ",\"weather_code\":0}}");
        assertNotNull(w);
        return w;
    }
}
