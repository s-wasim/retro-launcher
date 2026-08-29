package com.retro.launcher.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 51 hand-authored pixel marks from {@code design/Pixel App Logos.dc.html},
 * transcribed verbatim, plus the package names they belong to.
 *
 * <p>Each mark is a 12x12 bitmap of colour <em>roles</em>, not colours: the
 * palette in force at draw time supplies the actual ARGB, so a mark is correct
 * in all five palettes and both themes without a second copy. It is stamped
 * into the middle of the same 16x16 {@link PixelTile} silhouette every other
 * icon uses, so a marked app and a lettered one are the same shape on the grid.
 *
 * <p>An app with no mark here gets its letter — see DESIGN_NOTES §9 delta 2 on
 * why a glyph table can never cover arbitrary installed apps. This is the
 * short, curated head of that distribution, not an attempt at the tail.
 */
public final class PixelGlyphs {

    private PixelGlyphs() {}

    public static final int SIZE = PixelTile.SIZE;

    /** Where the 12x12 mark sits inside the 16x16 tile. */
    private static final int INSET = 2;
    private static final int GLYPH = 12;

    // Colour roles. 't' is the tile body the mark sits on; the rest name
    // Palette fields of the same letter.
    public static final char ROLE_TILE      = 't';
    public static final char ROLE_PRIMARY   = 'p';
    public static final char ROLE_ACCENT    = 'a';
    public static final char ROLE_SHADE     = 's';
    public static final char ROLE_HIGHLIGHT = 'h';
    public static final char ROLE_EMPTY     = '.';

    private static final Map<String, String[]> MARKS = new LinkedHashMap<>();
    private static final Map<String, String> BY_PACKAGE = new HashMap<>();

    static {
        // ---- Samsung / Android stock -------------------------------------
        mark("phone",
                "pph.........", "phhpp.......", "pppppp......", ".pppppp.....",
                "..pppppp....", "...ppppp....", "....ppppp...", ".....ppppp..",
                "......pppphp", "......pphhpp", ".......pppp.", "............");
        mark("messages",
                "..pppppppp..", ".pppppppppp.", "pppppppppppp", "pppppppppppp",
                "ppaapaapaapp", "ppaapaapaapp", "pppppppppppp", ".pppppppppp.",
                ".ssssssssss.", "..ppp.......", "..pp........", "............");
        mark("camera",
                "...aaa......", ".pppppppppp.", "pppppppppppp", "ppppsssspppp",
                "pppsshhssppp", "pppsshhssppp", "ppppsssspppp", "pppppppppppp",
                ".pppppppppp.", "..ssssssss..", "............", "............");
        mark("gallery",
                "pppppppppppp", "pssssssssssp", "pssssssaassp", "pssssssaassp",
                "pssssssssssp", "psssshhssssp", "pssshhhhsssp", "pshhhhhhhhsp",
                "phhhhhhhhhhp", "pppppppppppp", "............", "............");
        mark("clock",
                "....pppp....", "..pppppppp..", ".pppppppppp.", "ppppppappppp",
                "ppppppappppp", "ppppppaaappp", "pppppppppppp", "pppppppppppp",
                ".pppppppppp.", "..pppppppp..", "....ssss....", "............");
        mark("calculator",
                "pppppppppppp", "phhhhhhhhhhp", "phhhhhhhhhhp", "pppppppppppp",
                "paappaappaap", "pppppppppppp", "paappaappaap", "pppppppppppp",
                "paappaappaap", "pppppppppppp", "............", "............");
        mark("calendar",
                "..pp....pp..", "aaaaaaaaaaaa", "aaaaaaaaaaaa", "pppppppppppp",
                "phhpphhpphhp", "pppppppppppp", "phhpphhpphhp", "pppppppppppp",
                "phhpphhpphhp", "ssssssssssss", "............", "............");
        mark("settings",
                "...pp..pp...", "...pp..pp...", ".pppppppppp.", ".pppppppppp.",
                "ppppsssspppp", "pppsshhssppp", "pppsshhssppp", "ppppsssspppp",
                ".pppppppppp.", ".pppppppppp.", "...pp..pp...", "...pp..pp...");
        mark("internet",
                "....pppp....", "..pppphppp..", ".ppppphpppp.", "hhhhhhhhhhhh",
                "pppppphppppp", "pppppphppppp", "hhhhhhhhhhhh", ".ppppphpppp.",
                "..pppphppp..", "....ssss....", "............", "............");
        mark("contacts",
                "pppppppppppp", "pppphhhhpppp", "pppphhhhpppp", "pppppppppppp",
                "ppphhhhhhppp", "pphhhhhhhhpp", "pphhhhhhhhpp", "pppppppppppp",
                "paaaaaaaaaap", "pppppppppppp", "............", "............");
        mark("notes",
                "pppppppppppp", "ppppppppppaa", "phhhhhhhhhaa", "pppppppppppp",
                "phhhhhhhhhhp", "pppppppppppp", "phhhhhhhhhhp", "pppppppppppp",
                "phhhhhhppppp", "pppppppppppp", "ssssssssssss", "............");
        mark("weather",
                "..a......a..", "...aaaaaa...", "..aaaaaaaa..", "..aaaaaaaa..",
                "...aaaaaa...", "....pppp....", "..pppppppppp", ".pppppppppp.",
                "pppppppppppp", ".ssssssssss.", "............", "............");
        mark("health",
                "............", ".pppp..pppp.", "pppppppppppp", "pppppaappppp",
                "paaaappaaaap", "pppppppppppp", ".pppppppppp.", "..pppppppp..",
                "...ssssss...", ".....ss.....", "............", "............");
        mark("pay",
                "............", "pppppppppppp", "ssssssssssss", "ssssssssssss",
                "pppppppppppp", "paaapppppppp", "paaapppppppp", "pppphhhhhhpp",
                "pppppppppppp", "............", "............", "............");
        mark("store",
                "...pp..pp...", "..pp....pp..", "pppppppppppp", "pppppppppppp",
                "ppppaaaapppp", "pppaaaaaappp", "ppppaaaapppp", "pppppppppppp",
                "pppppppppppp", ".ssssssssss.", "............", "............");
        mark("files",
                "............", "aaaaa.......", "pppppppppppp", "phhhhhhhhhhp",
                "phhhhhhhhhhp", "phhhhhhhhhhp", "phhhhhhhhhhp", "phhhhhhhhhhp",
                "pppppppppppp", ".ssssssssss.", "............", "............");
        mark("email",
                "............", "aaaaaaaaaaaa", "phpppppppphp", "pphpppppphpp",
                "ppphpppphppp", "pppphhhhpppp", "pppppppppppp", "pppppppppppp",
                "pppppppppppp", "ssssssssssss", "............", "............");
        mark("music",
                "......ppppa.", "......ppaaa.", "......ppaaa.", "......pppa..",
                "......pp....", "......pp....", "......pp....", "..pppppp....",
                ".pppppppp...", ".pppppppp...", "..ssssss....", "............");
        mark("recorder",
                "....pppp....", "...pppppp...", "...pppppp...", "...pppppp...",
                "...pppppp...", "..a......a..", "..a......a..", "..aaaaaaaa..",
                ".....aa.....", "...aaaaaa...", "............", "............");
        mark("maps",
                "............", "pppppppppppp", "pppsppsppppp", "pppsppsppppp",
                "pppsppspaapp", "pppsppspaapp", "pppsppsppppp", "pppsppsppppp",
                "pppsppsppppp", "pppppppppppp", "............", "............");

        // ---- Social -------------------------------------------------------
        mark("whatsapp",
                "..pppppppp..", ".pppppppppp.", "pppaappppppp", "ppppaapppppp",
                "pppppaappppp", "ppppppaapppp", "pppppppaappp", ".pppppppppp.",
                ".ssssssssss.", "..ppp.......", "..pp........", "............");
        mark("snapchat",
                "...pppppp...", "..pppppppp..", ".pppppppppp.", ".pphhpphhpp.",
                ".pppppppppp.", ".pppppppppp.", ".pppaaaappp.", ".pppppppppp.",
                "pppppppppppp", "pp.pp.pp.pp.", "............", "............");
        mark("instagram",
                "..pppppppp..", ".pppppppppp.", "ppppppppphpp", "pppp....pppp",
                "ppp.aaaa.ppp", "ppp.aaaa.ppp", "ppp.aaaa.ppp", "pppp....pppp",
                "pppppppppppp", ".pppppppppp.", "..ssssssss..", "............");
        mark("facebook",
                "..aaaaaaaa..", "..aaaaaaaa..", "..ppp.......", "..ppp.......",
                "..pppppp....", "..pppppp....", "..ppp.......", "..ppp.......",
                "..ppp.......", "..sss.......", "............", "............");
        mark("x",
                ".pp......pp.", "..pp....pp..", "...pp..pp...", "....pppp....",
                ".....aa.....", ".....aa.....", "....pppp....", "...pp..pp...",
                "..pp....pp..", ".pp......pp.", "............", "............");
        mark("tiktok",
                ".....pppppp.", ".....pppaaa.", ".....pppaaa.", ".....ppppaa.",
                ".....ppp....", ".....ppp....", ".....ppp....", "..pppppp....",
                ".apppppp....", ".apppppp....", "..ssssss....", "............");
        mark("telegram",
                "..........aa", "........aapp", "......aapppp", "....aapppppp",
                "..aappppppp.", "aappppppp...", "...sshhpp...", "....sspp....",
                ".....ss.....", "............", "............", "............");
        mark("discord",
                "............", "..pppppppp..", ".pppppppppp.", "pppppppppppp",
                "ppaappppaapp", "ppaappppaapp", "pppppppppppp", ".pppppppppp.",
                "..pp....pp..", ".ss......ss.", "............", "............");
        mark("reddit",
                ".......aa...", ".......aa...", "..pppppppp..", ".pppppppppp.",
                "phhpppppphhp", "pppppppppppp", "pppaaaaaappp", ".pppppppppp.",
                "..ssssssss..", "............", "............", "............");
        mark("linkedin",
                "............", "............", ".........aaa", ".........aaa",
                ".....ppp.aaa", ".....ppp.aaa", ".ppp.ppp.aaa", ".ppp.ppp.aaa",
                ".ppp.ppp.aaa", ".ppp.ppp.aaa", "ssssssssssss", "............");
        mark("pinterest",
                "...pppppp...", "..pppppppp..", ".pppppppppp.", ".pphhhhhhpp.",
                ".pphhhhhhpp.", ".pppppppppp.", "..pppppppp..", "...aaaaaa...",
                ".....aa.....", ".....aa.....", ".....aa.....", "............");

        // ---- Work ---------------------------------------------------------
        mark("slack",
                "...pp..pp...", "...pp..pp...", "aaaaaaaaaaaa", "aaaaaaaaaaaa",
                "...pp..pp...", "...pp..pp...", "aaaaaaaaaaaa", "aaaaaaaaaaaa",
                "...pp..pp...", "...pp..pp...", "............", "............");
        mark("teams",
                "............", "..ppp..aaa..", "..ppp..aaa..", "............",
                ".ppppp.aaaaa", ".ppppp.aaaaa", ".ppppp.aaaaa", ".ppppp.aaaaa",
                ".sssss.sssss", "............", "............", "............");
        mark("zoom",
                "............", "ppppppppp...", "phhhhhhhp...", "phhhhhhhp.aa",
                "phhhhhhhpaaa", "phhhhhhhp.aa", "phhhhhhhp...", "ppppppppp...",
                "sssssssss...", "............", "............", "............");
        mark("gmail",
                "............", "pppppppppppp", "pappppppppap", "ppapppppappp",
                "pppappppappp", "ppppaaaapppp", "pppppppppppp", "pppppppppppp",
                "pppppppppppp", "ssssssssssss", "............", "............");
        mark("drive",
                "............", "....pppp....", "..pppppppp..", ".pppppppppp.",
                "pppppaappppp", "ppppaaaapppp", "pppaaaaaappp", "pppppaappppp",
                "pppppaappppp", ".ssssssssss.", "............", "............");
        mark("docs",
                "............", ".pppppppppp.", ".pppppppppp.", ".paaaaaaaap.",
                ".pppppppppp.", ".paaaaaaaap.", ".pppppppppp.", ".paaaaaaaap.",
                ".pppppppppp.", ".paaaappppp.", ".pppppppppp.", ".ssssssssss.");
        mark("notion",
                "pppppppppppp", "phhhhhhhhhhp", "phaahhhhaahp", "phaaahhhaahp",
                "phaahaahaahp", "phaahhaaaahp", "phaahhhhaahp", "phhhhhhhhhhp",
                "pppppppppppp", "ssssssssssss", "............", "............");
        mark("outlook",
                "............", "pppppppppppp", "paaaappppppp", "paapappppppp",
                "paapappppppp", "paaaappppppp", "pppppppppppp", "pppphhhhpppp",
                "pppppppppppp", "ssssssssssss", "............", "............");

        // ---- Media --------------------------------------------------------
        mark("youtube",
                "..pppppppp..", ".pppppppppp.", "ppppaapppppp", "ppppaaaapppp",
                "ppppaaaaaapp", "ppppaaaaaapp", "ppppaaaapppp", "ppppaapppppp",
                ".pppppppppp.", "..ssssssss..", "............", "............");
        mark("netflix",
                "ppp......ppp", "pppa.....ppp", "pppaa....ppp", "ppp.aa...ppp",
                "ppp..aa..ppp", "ppp...aa.ppp", "ppp....aappp", "ppp.....appp",
                "ppp......ppp", "ppp......ppp", "sss......sss", "............");
        mark("spotify",
                "....pppp....", "..pppppppp..", ".pppppppppp.", "ppaaaaaaaapp",
                "pppppppppppp", "pppaaaaaappp", "pppppppppppp", ".pppaaaappp.",
                ".pppppppppp.", "..pppppppp..", "....ssss....", "............");
        mark("prime",
                "............", "pppppppppppp", "phhhhhhhhhhp", "phhhpphhhhhp",
                "phhhppppphhp", "phhhppppphhp", "phhhpphhhhhp", "phhhhhhhhhhp",
                "pppppppppppp", ".aaaaaaaaaa.", "..aaaaaaaa..", "............");
        mark("disney",
                ".pp..pp..pp.", ".pp..pp..pp.", ".pppppppppp.", ".pppppppppp.",
                ".pppppppppp.", ".pppphhpppp.", ".pppphhpppp.", ".pppphhpppp.",
                "ssssssssssss", ".....aa.....", "...aaaaaa...", ".....aa.....");
        mark("twitch",
                "pppppppppppp", "phhhhhhhhhhp", "phhaahhaahhp", "phhaahhaahhp",
                "phhaahhaahhp", "phhhhhhhhhhp", "pppppppppppp", "..pppppppp..",
                "..pp........", "..ss........", "............", "............");

        // ---- Utility ------------------------------------------------------
        mark("uber",
                "............", "...pppppp...", "..pppppppp..", ".pphhhhhhpp.",
                ".pphhhhhhpp.", "pppppppppppp", "aappppppppaa", "pppppppppppp",
                ".pphhhhhhpp.", ".pphhhhhhpp.", "..ssssssss..", "............");
        mark("gmaps",
                "...pppppp...", "..pppppppp..", ".pppppppppp.", ".pppaaaappp.",
                ".pppaaaappp.", ".pppppppppp.", "..pppppppp..", "...pppppp...",
                "....pppp....", ".....pp.....", "....ssss....", "............");
        mark("chrome",
                "....pppp....", "..pppppppp..", ".pppppppppp.", "pppaaaaaappp",
                "ppaaaaaaaapp", "ssaaaaaaaass", "ppaaaaaaaapp", ".pppaaaappp.",
                ".pppssssppp.", "..pppppppp..", "....pppp....", "............");
        mark("playstore",
                "..pp........", "..pppp......", "..pppppp....", "..pppppppp..",
                "..pppppppppp", "..aaaaaaaaaa", "..aaaaaaaa..", "..aaaaaa....",
                "..aaaa......", "..aa........", "............", "............");
        mark("amazon",
                "............", "pppppppppppp", "pppppssppppp", "pppppssppppp",
                "pppppssppppp", "pppppssppppp", "pppppssppppp", "pppppssppppp",
                "ssssssssssss", ".aaaaaaaaaa.", "..aaaaaaaa..", "............");
        mark("paypal",
                "............", "..aaaaaaaa..", "..aaaaaaaa..", "pppppppppppp",
                "pppppppppppp", "pppppppppppp", "pppppppphhpp", "pppppppphhpp",
                "pppppppppppp", "ssssssssssss", "............", "............");

        // ---- Package names ------------------------------------------------
        // Samsung stock first (this launcher's home turf), then the AOSP and
        // Google equivalents that sit in the same slot on other devices.
        pkg("phone", "com.samsung.android.dialer", "com.android.dialer",
                "com.google.android.dialer", "com.android.phone",
                "com.android.server.telecom", "com.android.contacts.activities.TwelveKeyDialer");
        pkg("messages", "com.samsung.android.messaging", "com.google.android.apps.messaging",
                "com.android.mms", "com.android.messaging");
        pkg("camera", "com.sec.android.app.camera", "com.android.camera",
                "com.android.camera2", "com.google.android.GoogleCamera");
        pkg("gallery", "com.sec.android.gallery3d", "com.android.gallery3d",
                "com.google.android.apps.photos", "com.samsung.android.gallery");
        pkg("clock", "com.sec.android.app.clockpackage", "com.google.android.deskclock",
                "com.android.deskclock");
        pkg("calculator", "com.sec.android.app.popupcalculator", "com.google.android.calculator",
                "com.android.calculator2");
        pkg("calendar", "com.samsung.android.calendar", "com.google.android.calendar",
                "com.android.calendar");
        pkg("settings", "com.android.settings", "com.samsung.android.app.settings.bixby");
        pkg("internet", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
                "com.opera.browser", "com.brave.browser", "com.microsoft.emmx");
        pkg("contacts", "com.samsung.android.app.contacts", "com.android.contacts",
                "com.google.android.contacts");
        pkg("notes", "com.samsung.android.app.notes", "com.google.android.keep",
                "com.microsoft.office.onenote", "com.evernote");
        pkg("weather", "com.sec.android.daemonapp", "com.google.android.apps.weather",
                "com.samsung.android.weather");
        pkg("health", "com.sec.android.app.shealth", "com.google.android.apps.fitness",
                "com.samsung.android.app.health");
        pkg("pay", "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
                "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user");
        pkg("store", "com.sec.android.app.samsungapps");
        pkg("files", "com.sec.android.app.myfiles", "com.android.documentsui",
                "com.google.android.documentsui", "com.google.android.apps.nbu.files");
        pkg("email", "com.samsung.android.email.provider", "com.android.email",
                "com.samsung.android.email");
        pkg("music", "com.sec.android.app.music", "com.android.music",
                "com.google.android.apps.youtube.music", "com.sec.android.app.mv.player");
        pkg("recorder", "com.sec.android.app.voicenote", "com.google.android.apps.recorder",
                "com.android.soundrecorder");
        pkg("maps", "com.here.app.maps", "com.sygic.aura", "com.mapswithme.maps.pro",
                "org.osmand.plus", "com.waze");

        pkg("whatsapp", "com.whatsapp", "com.whatsapp.w4b");
        pkg("snapchat", "com.snapchat.android");
        pkg("instagram", "com.instagram.android", "com.instagram.lite");
        pkg("facebook", "com.facebook.katana", "com.facebook.lite", "com.facebook.orca");
        pkg("x", "com.twitter.android", "com.twitter.android.lite");
        pkg("tiktok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill");
        pkg("telegram", "org.telegram.messenger", "org.telegram.messenger.web",
                "org.thunderdog.challegram");
        pkg("discord", "com.discord");
        pkg("reddit", "com.reddit.frontpage");
        pkg("linkedin", "com.linkedin.android");
        pkg("pinterest", "com.pinterest");

        pkg("slack", "com.Slack");
        pkg("teams", "com.microsoft.teams", "com.microsoft.teams.free");
        pkg("zoom", "us.zoom.videomeetings");
        pkg("gmail", "com.google.android.gm", "com.google.android.gm.lite");
        pkg("drive", "com.google.android.apps.docs", "com.dropbox.android");
        pkg("docs", "com.google.android.apps.docs.editors.docs",
                "com.google.android.apps.docs.editors.sheets",
                "com.google.android.apps.docs.editors.slides",
                "com.microsoft.office.word");
        pkg("notion", "notion.id");
        pkg("outlook", "com.microsoft.office.outlook");

        pkg("youtube", "com.google.android.youtube", "app.revanced.android.youtube");
        pkg("netflix", "com.netflix.mediaclient");
        pkg("spotify", "com.spotify.music", "com.spotify.lite");
        pkg("prime", "com.amazon.avod.thirdpartyclient");
        pkg("disney", "com.disney.disneyplus");
        pkg("twitch", "tv.twitch.android.app");

        pkg("uber", "com.ubercab", "com.ubercab.eats", "com.careem.acma");
        pkg("gmaps", "com.google.android.apps.maps");
        pkg("chrome", "com.android.chrome", "com.chrome.beta", "com.chrome.dev");
        pkg("playstore", "com.android.vending");
        pkg("amazon", "com.amazon.mShop.android.shopping");
        pkg("paypal", "com.paypal.android.p2pmobile");
    }

    private static void mark(String name, String... rows) {
        if (rows.length != GLYPH) {
            throw new IllegalArgumentException(name + " has " + rows.length + " rows");
        }
        MARKS.put(name, rows);
    }

    private static void pkg(String name, String... packages) {
        for (String p : packages) BY_PACKAGE.put(p, name);
    }

    /** Every mark's name, in declaration order. */
    public static java.util.Set<String> names() {
        return Collections.unmodifiableSet(MARKS.keySet());
    }

    public static boolean has(String name) {
        return name != null && MARKS.containsKey(name);
    }

    /** @return the mark this package wears, or null — the caller draws a letter. */
    public static String forPackage(String packageName) {
        return packageName == null ? null : BY_PACKAGE.get(packageName);
    }

    /**
     * The mark stamped into the tile silhouette: a 16x16 grid of role chars,
     * {@link #ROLE_EMPTY} where nothing is drawn at all (the rounded corners).
     */
    public static char[][] compose(String name) {
        String[] rows = MARKS.get(name);
        if (rows == null) throw new IllegalArgumentException("no mark named " + name);
        return composeRows(rows);
    }

    /**
     * {@link #compose} for a 12x12 mark that is not in the table — a mark that
     * belongs to the launcher's own chrome rather than to an installed app.
     * The table stays exactly the design sheet's 51 app logos; the stamping
     * rule is the same for anything drawn on a tile.
     */
    public static char[][] composeRows(String[] rows) {
        if (rows.length != GLYPH) {
            throw new IllegalArgumentException("mark has " + rows.length + " rows");
        }

        char[][] grid = new char[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            java.util.Arrays.fill(grid[y], ROLE_EMPTY);
            int[] span = PixelTile.rowSpan(y);
            for (int x = span[0]; x <= span[1]; x++) grid[y][x] = ROLE_TILE;
        }
        for (int gy = 0; gy < GLYPH; gy++) {
            String row = rows[gy];
            for (int gx = 0; gx < GLYPH && gx < row.length(); gx++) {
                char c = row.charAt(gx);
                if (c == ROLE_EMPTY || c == ' ') continue;
                grid[gy + INSET][gx + INSET] = c;
            }
        }
        return grid;
    }

    /**
     * {@link #compose} flattened to horizontal runs of one role:
     * {@code {row, startCol, endColInclusive, role}}. Same shape as
     * {@link PixelTile#runs()} with the role appended, so the icon renderer is
     * one loop of {@code drawRect} either way.
     */
    public static int[][] runs(String name) {
        return runsOf(compose(name));
    }

    /** {@link #runs} for a mark composed by {@link #composeRows}. */
    public static int[][] runsRows(String[] rows) {
        return runsOf(composeRows(rows));
    }

    private static int[][] runsOf(char[][] grid) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            int x = 0;
            while (x < SIZE) {
                char c = grid[y][x];
                if (c == ROLE_EMPTY) { x++; continue; }
                int end = x;
                while (end + 1 < SIZE && grid[y][end + 1] == c) end++;
                out.add(new int[]{ y, x, end, c });
                x = end + 1;
            }
        }
        return out.toArray(new int[0][]);
    }
}
