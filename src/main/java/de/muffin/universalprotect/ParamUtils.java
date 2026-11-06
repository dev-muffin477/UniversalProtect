package de.muffin.universalprotect;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParamUtils {
    private static final Pattern TIME = Pattern.compile("(\\d+(\\.\\d+)?)([smhdw])");

    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        StringBuilder tags = new StringBuilder();
        
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.startsWith("#")) {
                tags.append(arg).append(",");
                continue;
            }
            int idx = arg.indexOf(':');
            if (idx > 0) {
                params.put(arg.substring(0, idx), arg.substring(idx + 1));
            }
        }
        if (tags.length() > 0) params.put("tags", tags.toString());
        return params;
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
                default -> 1_000L; // seconds
            };
            totalMs += (long) (value * multiplier);
        }
        return found ? totalMs : -1;
    }
}
