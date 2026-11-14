package de.muffin.universalprotect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups log types into storage categories for /up stats and /up clear. No platform dependencies. */
public final class LogStorage {

    public static final String OTHER = "other";
    public static final String AUDIT = "audit";

    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        add("blocks", "Block Logs", "block");
        add("containers", "Container Logs", "container", "inventory");
        add("items", "Item Logs", "item");
        add("entities", "Entity Logs", "kill", "spawn", "death", "frame");
        add("chat", "Chat Logs", "chat", "command");
        add("players", "Player Logs", "session", "teleport", "username");
        add("interactions", "Interaction Logs", "interact", "sign");
        add(AUDIT, "Audit Logs", "audit");
        LABELS.put(OTHER, "Other Logs");
    }

    private static void add(String name, String label, String... types) {
        CATEGORIES.put(name, List.of(types));
        LABELS.put(name, label);
    }

    public record Usage(String category, long rows, long bytes) {}

    private LogStorage() {}

    /** Rough row size: fixed columns plus the length of every text column. */
    public static final String USAGE_SQL = "SELECT type, COUNT(*), SUM(48 + COALESCE(length(data), 0) + COALESCE(length(old_data), 0)"
            + " + COALESCE(length(old_extra), 0) + COALESCE(length(meta), 0) + COALESCE(length(player_name), 0)"
            + " + COALESCE(length(world), 0) + COALESCE(length(action), 0)) FROM logs GROUP BY type";

    /** Names that /up clear accepts. The staff audit log is protected and cannot be cleared. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (String c : CATEGORIES.keySet()) if (!c.equals(AUDIT)) out.add(c);
        return out;
    }

    /** Accepts "blocks", "block", "block-logs"; returns null for unknown or protected categories. */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (s.endsWith("-logs")) s = s.substring(0, s.length() - 5);
        for (String c : names()) {
            if (c.equals(s) || c.equals(s + "s") || (c.equals("entities") && s.equals("entity"))
                    || (c.equals("interactions") && s.equals("interaction"))) return c;
        }
        return null;
    }

    public static String label(String category) { return LABELS.getOrDefault(category, category); }

    public static String categoryOf(String type) {
        if (type == null) return OTHER;
        for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet()) if (e.getValue().contains(type)) return e.getKey();
        return OTHER;
    }

    /** WHERE clause for a category, optionally only rows older than untilMillis (0 = all). Contains no parameters. */
    public static String whereFor(String category, long untilMillis) {
        List<String> types = CATEGORIES.get(category);
        if (types == null || category.equals(AUDIT)) throw new IllegalArgumentException("Unknown category: " + category);
        StringBuilder sb = new StringBuilder("type IN (");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(types.get(i)).append('\'');
        }
        sb.append(')');
        if (untilMillis > 0) sb.append(" AND time_millis < ").append(untilMillis);
        return sb.toString();
    }

    /** Reads the result of {@link #USAGE_SQL}, merged per category and sorted by size, largest first. */
    public static List<Usage> read(ResultSet rs) throws SQLException {
        Map<String, long[]> sums = new LinkedHashMap<>();
        while (rs.next()) {
            long[] a = sums.computeIfAbsent(categoryOf(rs.getString(1)), k -> new long[2]);
            a[0] += rs.getLong(2);
            a[1] += rs.getLong(3);
        }
        List<Usage> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sums.entrySet()) out.add(new Usage(e.getKey(), e.getValue()[0], e.getValue()[1]));
        out.sort((x, y) -> Long.compare(y.bytes(), x.bytes()));
        return out;
    }

    /** Scales the estimates so that they add up to the real file size. Unchanged if the size is unknown. */
    public static List<Usage> scale(List<Usage> in, long fileSize) {
        long sum = 0;
        for (Usage u : in) sum += u.bytes();
        if (fileSize <= 0 || sum <= 0) return in;
        List<Usage> out = new ArrayList<>();
        for (Usage u : in) out.add(new Usage(u.category(), u.rows(), Math.round((double) u.bytes() * fileSize / sum)));
        return out;
    }

    public static String format(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format(Locale.ROOT, "%.0f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }
}
