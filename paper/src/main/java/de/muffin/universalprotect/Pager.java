package de.muffin.universalprotect;

import java.util.*;

public final class Pager {
    public static final int LAST = -1;
    public static final int MAX_LINES = 20;
    private static final long KEEP_MS = 30 * 60_000L;
    private static final String RULE_SIDE = "<dark_green><strikethrough>   </strikethrough></dark_green>";
    private static final String RULE_FULL = "<dark_green><strikethrough>                                                </strikethrough></dark_green>";
    private static volatile int width = 300;

    private record Held(String title, List<String> pinned, List<String> lines, String extra, long time) {}

    private static final Map<String, Held> HELD = Collections.synchronizedMap(new LinkedHashMap<String, Held>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Held> eldest) { return size() > 64; }
    });

    private Pager() {}

    public static void configure(int px) { width = Math.max(120, Math.min(640, px)); }

    public static String kv(String label, String value) {
        return " <dark_green>▪</dark_green> <gray>" + label + ":</gray> <white>" + value + "</white>";
    }

    public static String head(String title) {
        return RULE_SIDE + " <green><bold>" + Msg.NAME + "</bold></green> <white>- " + title + "</white> " + RULE_SIDE;
    }

    public static String foot() { return RULE_FULL; }

    private static int cw(char c, boolean bold) {
        int w;
        switch (c) {
            case ' ' -> w = 4;
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> w = 3;
            case 'l' -> w = 4;
            case 'I', 't', '[', ']', '(', ')', '*', '"', '{', '}' -> w = 5;
            case '@', '~' -> w = 7;
            default -> w = c < 128 ? 6 : (c < 0x250 ? 6 : 8);
        }
        return bold ? w + 1 : w;
    }

    public static int height(String mini) {
        if (mini == null || mini.isEmpty()) return 1;
        int max = width;
        int lines = 1;
        int lineW = 0, wordW = 0;
        boolean bold = false;
        int n = mini.length();
        int i = 0;
        while (i < n) {
            char c = mini.charAt(i);
            if (c == '\\' && i + 1 < n) {
                wordW += cw(mini.charAt(i + 1), bold);
                i += 2;
                continue;
            }
            if (c == '<' && i + 1 < n && (mini.charAt(i + 1) == '/' || Character.isLetter(mini.charAt(i + 1)))) {
                int j = i + 1;
                boolean quote = false;
                while (j < n) {
                    char d = mini.charAt(j);
                    if (d == '\\' && j + 1 < n) { j += 2; continue; }
                    if (d == '\'') quote = !quote;
                    else if (d == '>' && !quote) break;
                    j++;
                }
                if (j < n) {
                    String tag = mini.substring(i + 1, j);
                    boolean closing = tag.startsWith("/");
                    String name = (closing ? tag.substring(1) : tag).toLowerCase(Locale.ROOT);
                    int colon = name.indexOf(':');
                    if (colon >= 0) name = name.substring(0, colon);
                    if (name.equals("bold") || name.equals("b")) bold = !closing;
                    else if (name.equals("reset")) bold = false;
                    else if (name.equals("newline") || name.equals("br")) {
                        if (lineW + wordW > max && lineW > 0) lines++;
                        lines++;
                        lineW = 0;
                        wordW = 0;
                    }
                    i = j + 1;
                    continue;
                }
            }
            if (c == ' ') {
                if (lineW > 0 && lineW + wordW > max) { lines++; lineW = 0; }
                lineW += wordW;
                wordW = 0;
                if (lineW > max) { lines += (lineW - 1) / max; lineW = lineW % max; }
                lineW += cw(' ', bold);
            } else {
                wordW += cw(c, bold);
            }
            i++;
        }
        if (lineW > 0 && lineW + wordW > max) { lines++; lineW = 0; }
        lineW += wordW;
        if (lineW > max) lines += (lineW - 1) / max;
        return lines;
    }

    public static int total(Collection<String> lines) {
        int t = 0;
        for (String s : lines) t += height(s);
        return t;
    }

    public static int fit(List<String> lines, int from, int budget) {
        int used = 0, n = 0;
        for (int i = from; i < lines.size(); i++) {
            int h = height(lines.get(i));
            if (n > 0 && used + h > budget) break;
            used += h;
            n++;
        }
        return n;
    }

    private static List<Integer> pack(List<String> lines, int budget) {
        List<Integer> starts = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            starts.add(i);
            i += Math.max(1, fit(lines, i, budget));
        }
        if (starts.isEmpty()) starts.add(0);
        return starts;
    }

    public static void send(Investigator.Out out, String title, List<String> pinned, List<String> lines, int page, String extra) {
        Held h = new Held(title, pinned == null ? List.of() : new ArrayList<>(pinned), new ArrayList<>(lines), extra, System.currentTimeMillis());
        HELD.put(out.key(), h);
        render(out, h, page);
    }

    public static void show(Investigator.Out out, int page) {
        Held h = HELD.get(out.key());
        if (h == null || System.currentTimeMillis() - h.time() > KEEP_MS) {
            out.err("Nothing to page through. Run /up help, /up investigate, /up timeline or /up audit first.");
            return;
        }
        render(out, h, page);
    }

    private static String nav(int p, int pages, String extra) {
        StringBuilder nav = new StringBuilder(" ");
        if (pages > 1) {
            if (p > 1) nav.append("<click:run_command:'/up view ").append(p - 1).append("'><hover:show_text:'Previous page'><green>« Prev</green></hover></click>");
            else nav.append("<dark_gray>« Prev</dark_gray>");
            nav.append(" <dark_gray>|</dark_gray> <white>").append(p).append("/").append(pages).append("</white> <dark_gray>|</dark_gray> ");
            if (p < pages) nav.append("<click:run_command:'/up view ").append(p + 1).append("'><hover:show_text:'Next page'><green>Next »</green></hover></click>");
            else nav.append("<dark_gray>Next »</dark_gray>");
        }
        if (extra != null) nav.append(pages > 1 ? "   " : "").append(extra.trim());
        return nav.toString();
    }

    private static void render(Investigator.Out out, Held h, int page) {
        int pinnedH = total(h.pinned());
        int headH = height(head(h.title() + " (99/99)"));
        int navH = height(nav(99, 99, h.extra()));
        int budget = Math.max(3, MAX_LINES - headH - pinnedH - navH - 1);
        List<Integer> starts = pack(h.lines(), budget);
        int pages = starts.size();
        int p = page == LAST ? pages : Math.max(1, Math.min(pages, page));
        out.raw(head(h.title() + (pages > 1 ? " <gray>(" + p + "/" + pages + ")</gray>" : "")));
        for (String s : h.pinned()) out.raw(s);
        int from = starts.get(p - 1);
        int to = p < pages ? starts.get(p) : h.lines().size();
        for (int i = from; i < to; i++) out.raw(h.lines().get(i));
        if (pages > 1 || h.extra() != null) out.raw(nav(p, pages, h.extra()));
        out.raw(RULE_FULL);
    }
}
