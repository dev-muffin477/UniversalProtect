package de.muffin.universalprotect;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ParamUtils {
    private static final Pattern TIME = Pattern.compile("(\\d+(\\.\\d+)?)([smhdw])");
    private static final Pattern PAGE = Pattern.compile("(\\d+)(?::(\\d+))?");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("user", "u"), Map.entry("users", "u"),
            Map.entry("time", "t"),
            Map.entry("radius", "r"),
            Map.entry("action", "a"), Map.entry("actions", "a"),
            Map.entry("include", "i"),
            Map.entry("exclude", "e"),
            Map.entry("filter", "f"),
            Map.entry("page", "p"));

    private ParamUtils() {}

    public static String canonicalKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(k, k);
    }

    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        StringBuilder tags = new StringBuilder();

        for (int i = 1; i < args.length; i++) {
            String raw = args[i];
            if (raw.startsWith("#")) {
                tags.append(raw.toLowerCase(Locale.ROOT)).append(',');
                continue;
            }
            if (raw.startsWith("--") && raw.length() > 2) {
                tags.append('#').append(raw.substring(2).toLowerCase(Locale.ROOT)).append(',');
                continue;
            }
            Matcher pm = PAGE.matcher(raw);
            if (pm.matches()) {
                params.put("p", pm.group(1));
                if (pm.group(2) != null) params.put("lines", pm.group(2));
                continue;
            }
            int idx = raw.indexOf(':');
            if (idx <= 0) continue;
            String key = canonicalKey(raw.substring(0, idx));
            String value = raw.substring(idx + 1);
            if (value.startsWith("\"")) {
                StringBuilder sb = new StringBuilder(value.substring(1));
                while (!endsQuoted(sb) && i + 1 < args.length) sb.append(' ').append(args[++i]);
                if (endsQuoted(sb)) sb.setLength(sb.length() - 1);
                value = sb.toString();
            }
            params.put(key, value);
        }
        if (tags.length() > 0) params.put("tags", tags.toString());
        return params;
    }

    private static boolean endsQuoted(CharSequence s) {
        return s.length() > 0 && s.charAt(s.length() - 1) == '"';
    }

    public static long parseTime(String timeStr) {
        if (timeStr == null) return -1;
        long totalMs = 0;
        Matcher m = TIME.matcher(timeStr.trim().toLowerCase(Locale.ROOT));
        boolean found = false;
        while (m.find()) {
            found = true;
            double value = Double.parseDouble(m.group(1));
            long multiplier = switch (m.group(3)) {
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 1_000L;
            };
            totalMs += (long) (value * multiplier);
        }
        return found ? totalMs : -1;
    }

    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
