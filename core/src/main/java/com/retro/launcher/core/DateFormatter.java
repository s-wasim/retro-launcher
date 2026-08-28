package com.retro.launcher.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The prototype's fmtDate() token formatter. Ported without java.time, which
 * a bare java-library module can use freely — but staying on plain ints/
 * Calendar-style fields keeps this identical in shape to the JS source and
 * to what HomeActivity already threads through as year/month0/day/dayOfWeek0.
 */
public final class DateFormatter {

    private DateFormatter() {}

    public static final String[] PRESETS = {
            "DD MMM YYYY", "MMM DD", "DDD DD/MM", "YYYY-MM-DD", "DOY / WK"
    };

    public static final String[] TOKENS = {
            "DD","D","MMM","MMMM","MM","YYYY","YY","DDD","DDDD","DOY","WK","/","-"," ",","
    };

    private static final String[] MON  = {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
    private static final String[] MONF = {"JANUARY","FEBRUARY","MARCH","APRIL","MAY","JUNE","JULY","AUGUST","SEPTEMBER","OCTOBER","NOVEMBER","DECEMBER"};
    private static final String[] DAY  = {"SUN","MON","TUE","WED","THU","FRI","SAT"};
    private static final String[] DAYF = {"SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"};

    // Longest-first, exactly as the prototype's replace() regex — matching is
    // order-based, not length-based, so DD must precede D and DDDD/DDD must
    // precede DD or the shorter tokens would swallow the longer ones.
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("DOY|DDDD|DDD|DD|D|MMMM|MMM|MM|YYYY|YY|WK");

    private static final int[] DAYS_IN_MONTH = {31,28,31,30,31,30,31,31,30,31,30,31};

    private static boolean isLeap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static int dayOfYear(int year, int month0, int day) {
        int doy = day;
        for (int m = 0; m < month0; m++) {
            doy += DAYS_IN_MONTH[m];
            if (m == 1 && isLeap(year)) doy += 1;
        }
        return doy;
    }

    public static String format(String pattern, int year, int month0, int day, int dayOfWeek0) {
        final int doy = dayOfYear(year, month0, day);
        final int wk = (int) Math.ceil(doy / 7.0);

        Matcher m = TOKEN_PATTERN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(pattern, last, m.start());
            out.append(replace(m.group(), year, month0, day, dayOfWeek0, doy, wk));
            last = m.end();
        }
        out.append(pattern, last, pattern.length());
        return out.toString();
    }

    private static String replace(String token, int year, int month0, int day,
                                  int dayOfWeek0, int doy, int wk) {
        switch (token) {
            case "DOY":  return "DAY " + doy;
            case "WK":   return "WK " + wk;
            case "DDDD": return DAYF[dayOfWeek0];
            case "DDD":  return DAY[dayOfWeek0];
            case "DD":   return pad2(day);
            case "D":    return String.valueOf(day);
            case "MMMM": return MONF[month0];
            case "MMM":  return MON[month0];
            case "MM":   return pad2(month0 + 1);
            case "YYYY": return String.valueOf(year);
            case "YY":   return String.valueOf(year).substring(Math.max(0, String.valueOf(year).length() - 2));
            default:     return token;
        }
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
