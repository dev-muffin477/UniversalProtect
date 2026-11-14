package de.muffin.universalprotect;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AnomalyDetector {

    public record Alert(String player, String rule, int level, String summary, String world, int x, int y, int z) {
        public String color() { return level >= 3 ? "red" : level == 2 ? "gold" : "yellow"; }
        public String label() { return level >= 3 ? "HIGH" : level == 2 ? "MEDIUM" : "LOW"; }
    }

    public interface ConfigReader {
        boolean bool(String path, boolean def);
        int integer(String path, int def);
        String string(String path, String def);
        List<String> list(String path);
    }

    private static final class Rule {
        final String id;
        final String verb;
        final Set<String> actions;
        final boolean sumAmount;
        final List<String> defaultMatch;
        final List<String> defaultExclude;
        final int defaultCount;
        final int defaultWindow;
        final int defaultRatio;
        final boolean defaultEnabled;
        volatile boolean enabled = true;
        volatile int threshold;
        volatile long windowMs;
        volatile int ratio;
        volatile List<String> match = List.of();
        volatile List<String> exclude = List.of();

        Rule(String id, boolean on, String verb, Set<String> actions, boolean sumAmount, List<String> match, List<String> exclude,
             int count, int window, int ratio) {
            this.id = id;
            this.defaultEnabled = on;
            this.verb = verb;
            this.actions = actions;
            this.sumAmount = sumAmount;
            this.defaultMatch = match;
            this.defaultExclude = exclude;
            this.defaultCount = count;
            this.defaultWindow = window;
            this.defaultRatio = ratio;
        }
    }

    private record Ev(long time, String action, String name, int amount, String world, int x, int y, int z) {}

    private static final class Tracker {
        final ArrayDeque<Ev> events = new ArrayDeque<>();
        final ArrayDeque<Ev> ores = new ArrayDeque<>();
        final ArrayDeque<long[]> buckets = new ArrayDeque<>();
        long lastExplosive;
        long last;
    }

    private static final String P = "detection.";
    private static final long BUCKET_MS = 10_000L;
    private static final int ORE_CAP = 400;
    private static final List<String> XRAY_MATCH = List.of("diamond_ore", "emerald_ore", "ancient_debris");
    private static final List<String> XRAY_EXPLOSIVE_EXEMPT = List.of("ancient_debris");
    private static final int EVENT_CAP = 8000;

    private static final List<Rule> RULES = List.of(
            new Rule("spawner-breaking", true, "broke %d spawners", Set.of(Actions.BROKE), false, List.of("spawner"), List.of(), 3, 600, 0),
            new Rule("tnt-placing", false, "placed %d TNT blocks", Set.of(Actions.PLACED), false, List.of("tnt"), List.of(), 40, 30, 0),
            new Rule("lava-placing", false, "placed lava %d times", Set.of(Actions.PLACED), false, List.of("lava"), List.of(), 15, 30, 0),
            new Rule("mass-breaking", false, "broke %d blocks", Set.of(Actions.BROKE), false, List.of(), List.of(), 3000, 10, 0),
            new Rule("container-looting", false, "took %d valuable items from containers", Set.of(Actions.TAKE), true,
                    List.of("diamond", "netherite", "elytra", "shulker_box", "totem", "enchanted_golden_apple", "beacon"), List.of(), 256, 60, 0));

    private volatile boolean xrayEnabled = true;
    private volatile int xrayMinVeins = 6;
    private volatile long xrayWindowMs = 1_200_000L;
    private volatile int xrayMaxBlocksPerVein = 40;
    private volatile int xrayVeinRadius = 6;
    private volatile long xrayCooldownMs = 600_000L;
    private volatile long xrayExplosiveGraceMs = 600_000L;
    private volatile List<String> xrayMatch = XRAY_MATCH;
    private volatile List<String> xrayExempt = XRAY_EXPLOSIVE_EXEMPT;

    private final Map<String, Tracker> trackers = new HashMap<>();
    private final Map<String, long[]> lastAlert = new HashMap<>();
    private volatile boolean enabled = true;
    private volatile long cooldownMs = 60_000L;
    private volatile long maxWindowMs = 300_000L;
    private volatile String webhook = "";
    private volatile Consumer<Alert> sink;
    private volatile HttpClient http;

    public static void registerDefaults(BiConsumer<String, Object> add) {
        add.accept(P + "enabled", true);
        add.accept(P + "cooldown-seconds", 60);
        add.accept(P + "discord-webhook", "");
        add.accept(P + "xray.enabled", true);
        add.accept(P + "xray.min-veins", 6);
        add.accept(P + "xray.window-seconds", 1200);
        add.accept(P + "xray.max-blocks-per-vein", 40);
        add.accept(P + "xray.vein-radius", 6);
        add.accept(P + "xray.cooldown-seconds", 600);
        add.accept(P + "xray.explosive-grace-seconds", 600);
        add.accept(P + "xray.match", new ArrayList<>(XRAY_MATCH));
        add.accept(P + "xray.explosive-exempt", new ArrayList<>(XRAY_EXPLOSIVE_EXEMPT));
        for (Rule r : RULES) {
            String k = P + r.id + ".";
            add.accept(k + "enabled", r.defaultEnabled);
            add.accept(k + "count", r.defaultCount);
            add.accept(k + "window-seconds", r.defaultWindow);
            if (r.defaultRatio > 0) add.accept(k + "min-ratio-percent", r.defaultRatio);
            add.accept(k + "match", new ArrayList<>(r.defaultMatch));
            if (!r.defaultExclude.isEmpty()) add.accept(k + "exclude", new ArrayList<>(r.defaultExclude));
        }
    }

    public void configure(ConfigReader c) {
        enabled = c.bool(P + "enabled", true);
        cooldownMs = Math.max(0, c.integer(P + "cooldown-seconds", 60)) * 1000L;
        String w = c.string(P + "discord-webhook", "");
        webhook = w == null ? "" : w.trim();
        long max = 1000L;
        xrayEnabled = c.bool(P + "xray.enabled", true);
        xrayMinVeins = Math.max(2, c.integer(P + "xray.min-veins", 6));
        xrayWindowMs = Math.max(30, c.integer(P + "xray.window-seconds", 1200)) * 1000L;
        xrayMaxBlocksPerVein = Math.max(1, c.integer(P + "xray.max-blocks-per-vein", 40));
        xrayVeinRadius = Math.max(1, c.integer(P + "xray.vein-radius", 6));
        xrayCooldownMs = Math.max(0, c.integer(P + "xray.cooldown-seconds", 600)) * 1000L;
        xrayExplosiveGraceMs = Math.max(0, c.integer(P + "xray.explosive-grace-seconds", 600)) * 1000L;
        xrayMatch = lower(c.list(P + "xray.match"), XRAY_MATCH);
        xrayExempt = lower(c.list(P + "xray.explosive-exempt"), XRAY_EXPLOSIVE_EXEMPT);
        max = Math.max(max, xrayWindowMs);
        for (Rule r : RULES) {
            String k = P + r.id + ".";
            r.enabled = c.bool(k + "enabled", r.defaultEnabled);
            r.threshold = Math.max(1, c.integer(k + "count", r.defaultCount));
            r.windowMs = Math.max(1, c.integer(k + "window-seconds", r.defaultWindow)) * 1000L;
            r.ratio = r.defaultRatio > 0 ? Math.max(0, Math.min(100, c.integer(k + "min-ratio-percent", r.defaultRatio))) : 0;
            r.match = lower(c.list(k + "match"), r.defaultMatch);
            r.exclude = lower(c.list(k + "exclude"), r.defaultExclude);
            max = Math.max(max, r.windowMs);
        }
        maxWindowMs = max;
    }

    private static List<String> lower(List<String> in, List<String> fallback) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out.isEmpty() ? fallback : out;
    }

    public void setSink(Consumer<Alert> sink) { this.sink = sink; }

    public void observe(LogEntry e) {
        if (!enabled || e == null) return;
        if (e.meta() != null && e.meta().startsWith("cmd:")) return;
        String player = e.player();
        String action = e.action();
        if (player == null || player.isEmpty() || player.charAt(0) == '#' || action == null) return;
        String raw = e.data() == null ? "" : e.data().toLowerCase(Locale.ROOT);
        String name;
        if (Actions.BROKE.equals(action) || Actions.PLACED.equals(action)) {
            if (!"block".equals(e.type()) || raw.startsWith("frame:")) return;
            int b = raw.indexOf('[');
            name = b >= 0 ? raw.substring(0, b) : raw;
            if (name.startsWith("minecraft:")) name = name.substring(10);
        } else if (Actions.TAKE.equals(action)) {
            int x = raw.lastIndexOf(" x");
            name = x > 0 ? raw.substring(0, x) : raw;
        } else {
            return;
        }
        String who = e.uuid() != null ? e.uuid() : player;
        Ev ev = new Ev(e.time(), action, name, Math.max(1, e.amount()), e.world(), e.x(), e.y(), e.z());
        synchronized (this) {
            Tracker t = trackers.computeIfAbsent(who, k -> new Tracker());
            t.events.addLast(ev);
            t.last = ev.time();
            while (!t.events.isEmpty() && (t.events.peekFirst().time() < ev.time() - maxWindowMs || t.events.size() > EVENT_CAP)) t.events.pollFirst();
            if (Actions.PLACED.equals(action) && isExplosive(name)) t.lastExplosive = ev.time();
            if (Actions.BROKE.equals(action)) trackBlock(t, ev, e, who);
            for (Rule r : RULES) {
                if (!r.enabled || !r.actions.contains(action) || !matches(r, name)) continue;
                evaluate(r, who, t, e, ev.time());
            }
            if (trackers.size() > 600) cleanup(ev.time());
        }
    }

    private static boolean isExplosive(String name) {
        return name.contains("tnt") || name.endsWith("_bed") || name.contains("respawn_anchor");
    }

    private void trackBlock(Tracker t, Ev ev, LogEntry e, String who) {
        long key = ev.time() / BUCKET_MS;
        long[] last = t.buckets.peekLast();
        if (last != null && last[0] == key) last[1]++;
        else t.buckets.addLast(new long[]{key, 1});
        long keep = (ev.time() - xrayWindowMs) / BUCKET_MS - 1;
        while (!t.buckets.isEmpty() && t.buckets.peekFirst()[0] < keep) t.buckets.pollFirst();
        if (!xrayEnabled) return;
        boolean rare = false;
        for (String m : xrayMatch) if (ev.name().contains(m)) { rare = true; break; }
        if (!rare) return;
        for (String x : xrayExempt) {
            if (ev.name().contains(x) && ev.time() - t.lastExplosive < xrayExplosiveGraceMs) return;
        }
        t.ores.addLast(ev);
        while (!t.ores.isEmpty() && (t.ores.peekFirst().time() < ev.time() - xrayWindowMs || t.ores.size() > ORE_CAP)) t.ores.pollFirst();
        evaluateXray(t, who, e, ev.time());
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private void evaluateXray(Tracker t, String who, LogEntry e, long now) {
        int n = t.ores.size();
        if (n < xrayMinVeins) return;
        List<Ev> list = new ArrayList<>(t.ores);
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int r = xrayVeinRadius;
        for (int i = 0; i < n; i++) {
            Ev a = list.get(i);
            for (int j = i + 1; j < n; j++) {
                Ev b = list.get(j);
                if (!Objects.equals(a.world(), b.world())) continue;
                if (Math.abs(a.x() - b.x()) <= r && Math.abs(a.y() - b.y()) <= r && Math.abs(a.z() - b.z()) <= r) {
                    int ra = find(parent, i), rb = find(parent, j);
                    if (ra != rb) parent[ra] = rb;
                }
            }
        }
        int veins = 0;
        for (int i = 0; i < n; i++) if (find(parent, i) == i) veins++;
        if (veins < xrayMinVeins) return;
        long from = now - xrayWindowMs;
        long blocks = 0;
        for (long[] b : t.buckets) if ((b[0] + 1) * BUCKET_MS > from) blocks += b[1];
        long perVein = blocks / veins;
        if (perVein > xrayMaxBlocksPerVein) return;
        int level = 1;
        if (veins >= xrayMinVeins * 2) level++;
        if (perVein * 3 <= xrayMaxBlocksPerVein) level++;
        String key = "xray|" + who;
        long[] prev = lastAlert.get(key);
        if (prev != null && now - prev[0] < xrayCooldownMs && veins < prev[1] * 2) return;
        lastAlert.put(key, new long[]{now, veins});
        Map<String, Long> parts = new HashMap<>();
        long oldest = now;
        for (Ev v : list) {
            parts.merge(v.name(), 1L, Long::sum);
            oldest = Math.min(oldest, v.time());
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(parts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("found ").append(veins).append(" separate rare ore veins (");
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getValue()).append("x ").append(sorted.get(i).getKey().replace('_', ' '));
        }
        long secs = Math.max(1, (now - oldest) / 1000);
        sb.append(") in ").append(secs >= 120 ? secs / 60 + "m" : secs + "s");
        sb.append(", only ").append(perVein).append(" blocks mined per vein");
        fire(new Alert(e.player(), "xray", Math.min(3, level), sb.toString(), e.world(), e.x(), e.y(), e.z()));
    }

    private static boolean matches(Rule r, String name) {
        for (String x : r.exclude) if (name.contains(x)) return false;
        List<String> m = r.match;
        if (m.isEmpty()) return true;
        for (String s : m) if (name.contains(s)) return true;
        return false;
    }

    private void evaluate(Rule r, String who, Tracker t, LogEntry e, long now) {
        long from = now - r.windowMs;
        long matched = 0, total = 0, oldest = now;
        Map<String, Long> parts = new HashMap<>();
        Iterator<Ev> it = t.events.descendingIterator();
        while (it.hasNext()) {
            Ev v = it.next();
            if (v.time() < from) break;
            if (Actions.BROKE.equals(v.action())) total++;
            if (!r.actions.contains(v.action()) || !matches(r, v.name())) continue;
            long w = r.sumAmount ? v.amount() : 1;
            matched += w;
            oldest = Math.min(oldest, v.time());
            parts.merge(v.name(), w, Long::sum);
        }
        if (matched < r.threshold) return;
        long pct = total == 0 ? 100 : Math.min(100, matched * 100 / total);
        if (r.ratio > 0 && pct < r.ratio && matched < (long) r.threshold * 3) return;

        int level = matched >= (long) r.threshold * 4 ? 3 : matched >= (long) r.threshold * 2 ? 2 : 1;
        if (r.ratio > 0) level = Math.max(level, pct >= 60 ? 3 : pct >= 40 ? 2 : 1);

        String key = r.id + "|" + who;
        long[] prev = lastAlert.get(key);
        if (prev != null && now - prev[0] < cooldownMs && matched < prev[1] * 2) return;
        lastAlert.put(key, new long[]{now, matched});

        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, r.verb, matched));
        if (parts.size() > 1) {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(parts.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            sb.append(" (");
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sorted.get(i).getValue()).append("x ").append(sorted.get(i).getKey().replace('_', ' '));
            }
            sb.append(')');
        }
        sb.append(" in ").append(Math.max(1, (now - oldest) / 1000)).append("s");
        if (r.ratio > 0) sb.append(", ").append(pct).append("% of ").append(total).append(" blocks");
        fire(new Alert(e.player(), r.id, level, sb.toString(), e.world(), e.x(), e.y(), e.z()));
    }

    private void cleanup(long now) {
        trackers.values().removeIf(t -> now - t.last > maxWindowMs * 2);
        lastAlert.values().removeIf(a -> now - a[0] > 3_600_000L);
    }

    private void fire(Alert a) {
        Consumer<Alert> s = sink;
        if (s != null) {
            try { s.accept(a); } catch (Throwable ignored) { }
        }
    }

    private void sendWebhook(Alert a) {
        String url = webhook;
        if (url.isEmpty() || !url.startsWith("https://")) return;
        try {
            HttpClient c = http;
            if (c == null) http = c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String text = "⚠ [" + a.label() + "] **" + a.player() + "** " + a.summary() + " (" + a.world() + " " + a.x() + " " + a.y() + " " + a.z() + ")";
            String body = "{\"content\":\"" + json(text) + "\",\"allowed_mentions\":{\"parse\":[]}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            c.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) { }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.toString();
    }
}
