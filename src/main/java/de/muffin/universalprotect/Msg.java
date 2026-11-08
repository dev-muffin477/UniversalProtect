package de.muffin.universalprotect;


import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Einheitliches Chat-Design: Grün & Weiß. Alle Ausgaben des Plugins laufen hierüber. */
public final class Msg {
    public static final String NAME = "UniversalProtect";
    public static final String PREFIX = "<green><bold>" + NAME + "</bold></green> <dark_gray>»</dark_gray> ";
    private static final String RULE_SIDE = "<dark_green><strikethrough>          </strikethrough></dark_green>";
    private static final String RULE_FULL = "<dark_green><strikethrough>                                                </strikethrough></dark_green>";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Msg() {}

    public static String esc(String s) { return s == null ? "" : Mini.escape(s); }

    /** Nachricht mit Plugin-Präfix. */
    public static void send(Sender to, String mini) { to.sendMiniSafe(PREFIX + mini); }

    /** Nachricht ohne Präfix. */
    public static void raw(Sender to, String mini) { to.sendMiniSafe(mini); }

    public static void ok(Sender to, String text) { send(to, "<green>" + text + "</green>"); }

    public static void err(Sender to, String text) { send(to, "<red>" + text + "</red>"); }

    public static void info(Sender to, String text) { send(to, "<gray>" + text + "</gray>"); }

    /** Kopfzeile: ───── UniversalProtect - Titel ───── */
    public static void header(Sender to, String title) {
        raw(to, "");
        raw(to, RULE_SIDE + " <green><bold>" + NAME + "</bold></green> <white>- " + title + "</white> " + RULE_SIDE);
    }

    public static void footer(Sender to) { raw(to, RULE_FULL); }

    /** Zeile "▪ Label: Wert". */
    public static void kv(Sender to, String label, String value) {
        raw(to, " <dark_green>▪</dark_green> <gray>" + label + ":</gray> <white>" + value + "</white>");
    }

    /** Zeile "  Label  Wert" für Aufzählungen. */
    public static void item(Sender to, String left, String right) {
        raw(to, "   <green>" + left + "</green> <dark_gray>»</dark_gray> <white>" + right + "</white>");
    }

    public static String date(long millis) { return DATE.format(Instant.ofEpochMilli(millis)); }

    /** Kompakte Altersangabe: 12s, 5m, 3.2h, 1.5d. */
    public static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return String.format(Locale.ROOT, "%.1fh", s / 3600.0);
        return String.format(Locale.ROOT, "%.1fd", s / 86400.0);
    }

    /** "minecraft:oak_log[axis=y]" -> "oak_log". */
    public static String cleanBlock(String data) {
        if (data == null) return "?";
        String d = data.replace("minecraft:", "");
        int b = d.indexOf('[');
        return b >= 0 ? d.substring(0, b) : d;
    }
}
