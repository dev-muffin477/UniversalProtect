package de.muffin.universalprotect;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Msg {
    public static final String NAME = "UniversalProtect";
    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<green><bold>" + NAME + "</bold></green> <dark_gray>»</dark_gray> ";
    private static final String RULE_SIDE = "<dark_green><strikethrough>          </strikethrough></dark_green>";
    private static final String RULE_FULL = "<dark_green><strikethrough>                                                </strikethrough></dark_green>";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Msg() {}

    public static String esc(String s) { return s == null ? "" : MM.escapeTags(s); }

    public static void send(CommandSender to, String mini) { to.sendMessage(MM.deserialize(PREFIX + mini)); }

    public static void raw(CommandSender to, String mini) { to.sendMessage(MM.deserialize(mini)); }

    public static void ok(CommandSender to, String text) { send(to, "<green>" + text + "</green>"); }

    public static void err(CommandSender to, String text) { send(to, "<red>" + text + "</red>"); }

    public static void info(CommandSender to, String text) { send(to, "<gray>" + text + "</gray>"); }

    public static void header(CommandSender to, String title) {
        raw(to, "");
        raw(to, RULE_SIDE + " <green><bold>" + NAME + "</bold></green> <white>- " + title + "</white> " + RULE_SIDE);
    }

    public static void footer(CommandSender to) { raw(to, RULE_FULL); }

    public static void kv(CommandSender to, String label, String value) {
        raw(to, " <dark_green>▪</dark_green> <gray>" + label + ":</gray> <white>" + value + "</white>");
    }

    public static void item(CommandSender to, String left, String right) {
        raw(to, "   <green>" + left + "</green> <dark_gray>»</dark_gray> <white>" + right + "</white>");
    }

    public static String date(long millis) { return DATE.format(Instant.ofEpochMilli(millis)); }

    public static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return String.format(Locale.ROOT, "%.1fh", s / 3600.0);
        return String.format(Locale.ROOT, "%.1fd", s / 86400.0);
    }

    public static String cleanBlock(String data) {
        if (data == null) return "?";
        String d = data.replace("minecraft:", "");
        int b = d.indexOf('[');
        return b >= 0 ? d.substring(0, b) : d;
    }
}
