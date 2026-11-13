package de.muffin.universalprotect;

import java.util.*;

public final class Investigator {
    public interface Out {
        void raw(String mini);
        void err(String text);
        String key();
    }

    public record Spot(String world, int x, int y, int z) {}

    private static final String RULE_SIDE = "<dark_green><strikethrough>          </strikethrough></dark_green>";
    private static final String RULE_FULL = "<dark_green><strikethrough>                                                </strikethrough></dark_green>";
    private static final String NOISE = "action NOT IN ('chat','command','join','quit','username','agg_broke','agg_placed','staff','teleport') AND type <> 'audit'";
    private static final int ROW_CAP = 30_000;
    private static final int TL_CAP = 20_000;
    private static final Set<String> TL_FLAGS = Set.of("#command", "#silent", "#count", "#summary", "#verbose", "#optimize", "#preview", "#global", "#dry");
    private static final int MAX_LINES = 30;
    private static final long RUN_GAP = 60_000L;
    private static final long SESSION_GAP = 5 * 60_000L;
    private static final long DAY = 86_400_000L;
    private static final int SUSPECT_MIN = 15;
    private static final Set<String> TIMELINE_DEFAULT = new LinkedHashSet<>(List.of(Actions.JOIN, Actions.QUIT, Actions.PLACED, Actions.BROKE,
            Actions.PUT, Actions.TAKE, Actions.TELEPORT, Actions.KILL, Actions.DEATH, Actions.COMMAND, Actions.SIGN));

    private final DatabaseManager db;
    private final java.util.logging.Logger log;

    public Investigator(DatabaseManager db, java.util.logging.Logger log) {
        this.db = db;
        this.log = log;
    }

    static void header(Out out, String title) {
        out.raw("");
        out.raw(RULE_SIDE + " <green><bold>" + Msg.NAME + "</bold></green> <white>- " + title + "</white> " + RULE_SIDE);
    }

    static void footer(Out out) { out.raw(RULE_FULL); }

    private static void section(List<String> L, String title) {
        L.add("");
        L.add(" <green><bold>" + title + "</bold></green>");
    }

    private static final class Stat {
        final String name;
        final Causes.Kind kind;
        int broke, placed, kills, spawns, caused, causedInferred;
        long takeAmt, putAmt;
        long first = Long.MAX_VALUE, last;
        final List<Long> stamps = new ArrayList<>();

        Stat(String name) {
            this.name = name;
            this.kind = Causes.kind(name);
        }

        double score() { return broke + placed / 2.0 + takeAmt / 2.0 + kills * 3.0 + caused; }
    }

    private static final class Box {
        long take, put;
        final Set<String> players = new LinkedHashSet<>();
        final Map<String, Long> items = new LinkedHashMap<>();
        LogRow head;
    }

    private static final class Run {
        String kind;
        String actor;
        String meta;
        long first, last;
        int rows;
        long amount;
        final Map<String, Long> objs = new LinkedHashMap<>();
        LogRow head;
        long weight() { return amount > 0 ? amount : rows; }
    }

    private static String ts(long t) { return Msg.date(t); }

    private static String hms(long t) { return Msg.date(t).substring(11); }

    private static LogRow withMeta(LogRow r, String meta) {
        return new LogRow(r.id(), r.time(), r.type(), r.player(), r.uuid(), r.action(), r.data(), r.world(), r.x(), r.y(), r.z(),
                r.oldData(), r.oldExtra(), r.amount(), meta, r.rolledBack());
    }

    private static String itemName(String data) {
        if (data == null) return "?";
        return data.replaceAll(" x\\d+$", "").toLowerCase(Locale.ROOT);
    }

    private static String blockName(String data) {
        if (data == null) return "?";
        if (data.startsWith("frame:") || FrameState.isFrame(data)) return "item frame";
        return Msg.cleanBlock(data);
    }

    private static String victim(String data) {
        if (data == null) return "?";
        if (data.startsWith("player:")) return "player " + data.substring(7);
        int c = data.indexOf(':');
        return (c > 0 ? data.substring(0, c) : data).toLowerCase(Locale.ROOT);
    }

    private static String kindOf(String action) {
        if (action == null) return "other";
        return switch (action) {
            case Actions.BROKE -> "broke";
            case Actions.PLACED -> "placed";
            case Actions.PUT -> "put";
            case Actions.TAKE -> "take";
            case Actions.KILL -> "kill";
            case Actions.SPAWN -> "spawn";
            default -> "other";
        };
    }

    private static String objectOf(LogRow r) {
        return switch (r.action()) {
            case Actions.BROKE, Actions.PLACED -> blockName(r.data());
            case Actions.PUT, Actions.TAKE -> itemName(r.data());
            case Actions.KILL, Actions.SPAWN -> victim(r.data());
            default -> "";
        };
    }

    private static String top(Map<String, Long> m, int n) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Msg.esc(l.get(i).getKey())).append(" ×").append(l.get(i).getValue());
        }
        if (l.size() > n) sb.append(", …");
        return sb.toString();
    }

    private static List<LogRow> infer(List<LogRow> raw) {
        List<LogRow> out = new ArrayList<>(raw.size());
        LogRow lastTnt = null;
        for (LogRow r : raw) {
            String a = r.action();
            if (Actions.PLACED.equals(a) && Causes.isPlayer(r.player()) && r.data() != null && r.data().toLowerCase(Locale.ROOT).contains("tnt")) {
                lastTnt = r;
            }
            if (Actions.BROKE.equals(a) && lastTnt != null && Causes.by(r.meta()) == null && r.player() != null
                    && r.player().startsWith("#") && r.player().toLowerCase(Locale.ROOT).contains("tnt")
                    && r.time() - lastTnt.time() <= 120_000L && r.time() >= lastTnt.time()) {
                out.add(withMeta(r, "likely:" + lastTnt.player()));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Run> condense(List<LogRow> rows) {
        List<Run> runs = new ArrayList<>();
        Map<String, Run> open = new HashMap<>();
        for (LogRow r : rows) {
            String kind = kindOf(r.action());
            String by = Causes.by(r.meta());
            String key = (r.player() == null ? "?" : r.player()) + "|" + kind + "|" + (by == null ? "" : (Causes.inferred(r.meta()) ? "~" : "") + by);
            Run run = "other".equals(kind) ? null : open.get(key);
            if (run == null || r.time() - run.last > RUN_GAP) {
                run = new Run();
                run.kind = kind;
                run.actor = r.player();
                run.meta = r.meta();
                run.first = r.time();
                run.head = r;
                runs.add(run);
                if (!"other".equals(kind)) open.put(key, run);
            }
            run.last = r.time();
            run.rows++;
            if (Actions.PUT.equals(r.action()) || Actions.TAKE.equals(r.action())) run.amount += Math.max(1, r.amount());
            String obj = objectOf(r);
            if (!obj.isEmpty()) run.objs.merge(obj, Actions.PUT.equals(r.action()) || Actions.TAKE.equals(r.action()) ? (long) Math.max(1, r.amount()) : 1L, Long::sum);
        }
        return runs;
    }

    private static String spotClick(LogRow r) {
        String world = r.world() == null ? "" : r.world().replace("'", "");
        if (world.isEmpty()) return "";
        return " <dark_gray>[<hover:show_text:'Teleport • " + Msg.esc(world) + "'><click:run_command:'/up tp " + world + ' ' + r.x() + ' ' + r.y() + ' ' + r.z()
                + "'><gray>" + r.x() + ' ' + r.y() + ' ' + r.z() + "</gray></click></hover>]</dark_gray>";
    }

    private static String describe(Run run) {
        String list = run.objs.isEmpty() ? "" : " <gray>(" + top(run.objs, 3) + ")</gray>";
        return switch (run.kind) {
            case "broke" -> "<red>broke</red> <white>" + run.rows + "</white> block" + (run.rows == 1 ? "" : "s") + list;
            case "placed" -> "<green>placed</green> <white>" + run.rows + "</white> block" + (run.rows == 1 ? "" : "s") + list;
            case "put" -> "<green>put</green> <white>" + run.amount + "</white> item" + (run.amount == 1 ? "" : "s") + " <gray>into containers</gray>" + list;
            case "take" -> "<red>took</red> <white>" + run.amount + "</white> item" + (run.amount == 1 ? "" : "s") + " <gray>from containers</gray>" + list;
            case "kill" -> "<red>killed</red> <white>" + run.rows + "</white>" + list;
            case "spawn" -> "<green>spawned</green> <white>" + run.rows + "</white>" + list;
            default -> LookupView.describe(run.head);
        };
    }

    private static String line(Run run, boolean withActor) {
        String t1 = hms(run.first), t2 = hms(run.last);
        String time = t1.equals(t2) ? t1 : t1 + "–" + t2;
        StringBuilder sb = new StringBuilder(" <hover:show_text:'").append(ts(run.first)).append("'><green>").append(time).append("</green></hover> <dark_gray>│</dark_gray> ");
        if (withActor) sb.append(Causes.label(run.actor, run.meta)).append(' ');
        else if (Causes.by(run.meta) != null || !Causes.isPlayer(run.actor)) sb.append(Causes.label(run.actor, run.meta)).append(' ');
        sb.append(describe(run)).append(spotClick(run.head));
        return sb.toString();
    }

    private static List<long[]> intervals(List<Long> stamps) {
        List<long[]> out = new ArrayList<>();
        for (long t : stamps) {
            if (!out.isEmpty() && t - out.get(out.size() - 1)[1] <= SESSION_GAP) out.get(out.size() - 1)[1] = t;
            else out.add(new long[]{t, t});
        }
        return out;
    }

    private static long overlap(List<long[]> a, List<long[]> b) {
        long total = 0;
        for (long[] x : a) for (long[] y : b) {
            long s = Math.max(x[0], y[0]), e = Math.min(x[1], y[1]);
            if (e > s) total += e - s;
        }
        return total;
    }

    private static String clean(String s) { return s == null ? "" : s.replace("'", "").replace(" ", ""); }

    public void investigate(Out out, Spot at, Map<String, String> params) {
        int radius = 15;
        try { if (params.containsKey("r")) radius = Math.max(1, Math.min(150, Integer.parseInt(params.get("r").trim()))); }
        catch (NumberFormatException ignored) {  }
        long span = params.containsKey("t") ? ParamUtils.parseTime(params.get("t")) : 2L * 3_600_000L;
        if (span <= 0) span = 2L * 3_600_000L;
        span = Math.min(span, 30L * DAY);
        final int fr = radius;
        final long fs = span;
        final List<String> users = ParamUtils.split(params.get("u"));
        db.submit(() -> {
            try {
                run(out, at, fr, fs, users);
            } catch (Exception e) {
                log.warning("[Investigate] Failed: " + e.getMessage());
                e.printStackTrace();
                out.err("Investigation failed, see console.");
            }
        });
    }

    private void run(Out out, Spot at, int radius, long span, List<String> users) throws Exception {
        db.flush();
        List<String> L = new ArrayList<>();
        long now = System.currentTimeMillis();
        Query q = new Query();
        q.worlds = List.of(at.world());
        q.x = at.x(); q.y = at.y(); q.z = at.z();
        q.radius = radius;
        q.since = now - span;
        q.users = users;
        List<LogRow> raw = db.selectRows(q, true, ROW_CAP, 0, NOISE, null);
        if (raw.isEmpty()) { out.err("No logged changes in this area for the selected period."); return; }
        List<LogRow> rows = infer(raw);

        Map<String, Stat> stats = new LinkedHashMap<>();
        Map<Causes.Kind, int[]> kinds = new EnumMap<>(Causes.Kind.class);
        int traced = 0;
        Map<String, Box> boxes = new LinkedHashMap<>();
        Map<String, Map<String, Long>> kills = new LinkedHashMap<>();
        int spawns = 0;
        long firstAll = Long.MAX_VALUE, lastAll = 0;
        for (LogRow r : rows) {
            String who = r.player() == null ? "?" : r.player();
            Stat s = stats.computeIfAbsent(who, Stat::new);
            s.stamps.add(r.time());
            s.first = Math.min(s.first, r.time());
            s.last = Math.max(s.last, r.time());
            firstAll = Math.min(firstAll, r.time());
            lastAll = Math.max(lastAll, r.time());
            String a = r.action();
            String by = Causes.by(r.meta());
            switch (a) {
                case Actions.BROKE -> {
                    s.broke++;
                    kinds.computeIfAbsent(s.kind, k -> new int[2])[0]++;
                    if (by != null) {
                        kinds.computeIfAbsent(s.kind, k -> new int[2])[1]++;
                        Stat b = stats.computeIfAbsent(by, Stat::new);
                        b.caused++;
                        if (Causes.inferred(r.meta())) b.causedInferred++;
                        traced++;
                    }
                }
                case Actions.PLACED -> {
                    s.placed++;
                    kinds.computeIfAbsent(s.kind, k -> new int[2])[0]++;
                }
                case Actions.PUT, Actions.TAKE -> {
                    long amt = Math.max(1, r.amount());
                    if (Actions.PUT.equals(a)) s.putAmt += amt; else s.takeAmt += amt;
                    String key = r.world() + ":" + r.x() + ":" + r.y() + ":" + r.z();
                    Box box = boxes.computeIfAbsent(key, k -> new Box());
                    if (box.head == null) box.head = r;
                    if (Actions.PUT.equals(a)) box.put += amt; else box.take += amt;
                    box.players.add(who);
                    box.items.merge(itemName(r.data()), amt, Long::sum);
                }
                case Actions.KILL -> {
                    s.kills++;
                    kills.computeIfAbsent(who, k -> new LinkedHashMap<>()).merge(victim(r.data()), 1L, Long::sum);
                }
                case Actions.SPAWN -> { s.spawns++; spawns++; }
                default -> {  }
            }
        }

        List<Stat> suspects = new ArrayList<>();
        for (Stat s : stats.values()) if (s.kind == Causes.Kind.PLAYER && s.score() >= SUSPECT_MIN) suspects.add(s);
        suspects.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (suspects.size() > 5) suspects = new ArrayList<>(suspects.subList(0, 5));

        long span2 = Math.max(60_000L, lastAll - firstAll);
        L.add(" <dark_green>▪</dark_green> <gray>Area:</gray> <white>" + Msg.esc(at.world()) + " " + at.x() + " " + at.y() + " " + at.z()
                + "</white> <gray>radius</gray> <white>" + radius + "</white>");
        L.add(" <dark_green>▪</dark_green> <gray>Period:</gray> <white>last " + Msg.ago(span) + "</white> <gray>(events from</gray> <white>" + hms(firstAll)
                + "</white> <gray>to</gray> <white>" + hms(lastAll) + "</white><gray>)</gray>");
        int actorsPlayers = 0;
        for (Stat s : stats.values()) if (s.kind == Causes.Kind.PLAYER) actorsPlayers++;
        L.add(" <dark_green>▪</dark_green> <gray>Entries:</gray> <white>" + rows.size() + "</white> <gray>from</gray> <white>" + actorsPlayers + "</white> <gray>player(s) and</gray> <white>"
                + (stats.size() - actorsPlayers) + "</white> <gray>other cause(s)</gray>" + (raw.size() >= ROW_CAP ? " <red>(truncated)</red>" : ""));

        section(L, "Suspects");
        if (suspects.isEmpty()) {
            L.add("   <gray>No player stands out. Changes are small or caused by the environment.</gray>");
        }
        for (Stat s : suspects) {
            double sc = s.score();
            String color = sc >= 300 ? "red" : sc >= 80 ? "gold" : "yellow";
            String label = sc >= 300 ? "HIGH" : sc >= 80 ? "MEDIUM" : "LOW";
            List<String> reasons = new ArrayList<>();
            if (s.broke >= 30) {
                long rate = s.broke * 60_000L / Math.max(60_000L, s.last - s.first);
                reasons.add(s.broke + " blocks broken" + (rate >= 30 ? " (" + rate + "/min)" : ""));
            }
            if (s.placed >= 60) reasons.add(s.placed + " blocks placed");
            if (s.takeAmt >= 32) reasons.add(s.takeAmt + " items taken from containers");
            if (s.kills >= 3) reasons.add(s.kills + " kills");
            if (s.caused >= 5) reasons.add(s.caused + " blocks destroyed by explosions" + (s.causedInferred == s.caused ? " (probably)" : ""));
            if (reasons.isEmpty()) reasons.add(s.broke + " broken, " + s.placed + " placed");
            String nm = clean(s.name);
            L.add("   <" + color + ">" + label + "</" + color + "> <white>" + Msg.esc(s.name) + "</white> <gray>" + Msg.esc(String.join(", ", reasons)) + "</gray>");
            L.add("     <dark_gray>[</dark_gray><click:run_command:'/up timeline " + nm + " t:" + Math.max(1, span / 60_000L) + "m'><aqua>Timeline</aqua></click><dark_gray>]</dark_gray> "
                    + "<dark_gray>[</dark_gray><click:run_command:'/up rollback u:" + nm + " t:" + Math.max(1, span / 60_000L) + "m r:" + radius + " #preview'><red>Preview rollback</red></click><dark_gray>]</dark_gray>");
        }

        section(L, "Causes (block changes)");
        StringBuilder cs = new StringBuilder("  ");
        for (Causes.Kind k : Causes.Kind.values()) {
            int[] c = kinds.get(k);
            if (c == null || c[0] == 0) continue;
            cs.append(" <gray>").append(k.label()).append("</gray> <white>").append(c[0]).append("</white>");
            if (c[1] > 0) cs.append(" <dark_gray>(").append(c[1]).append(" traced to players)</dark_gray>");
            cs.append(" <dark_gray>•</dark_gray>");
        }
        L.add(cs.length() > 3 ? cs.substring(0, cs.length() - " <dark_gray>•</dark_gray>".length()) : "   <gray>No block changes.</gray>");

        List<Map.Entry<String, Box>> bl = new ArrayList<>(boxes.entrySet());
        bl.sort((a, b) -> Long.compare(b.getValue().take + b.getValue().put, a.getValue().take + a.getValue().put));
        if (!bl.isEmpty()) {
            section(L, "Containers");
            for (int i = 0; i < Math.min(5, bl.size()); i++) {
                Box b = bl.get(i).getValue();
                Map<String, Long> items = b.items;
                L.add("   <dark_gray>•</dark_gray> " + (b.take > 0 ? "<red>took " + b.take + "</red> " : "") + (b.put > 0 ? "<green>put " + b.put + "</green> " : "")
                        + "<gray>(" + top(items, 3) + ")</gray> <dark_gray>by</dark_gray> <white>" + Msg.esc(String.join(", ", b.players)) + "</white>" + spotClick(b.head));
            }
            if (bl.size() > 5) L.add("   <dark_gray>… " + (bl.size() - 5) + " more containers</dark_gray>");
        }

        if (!kills.isEmpty() || spawns > 0) {
            section(L, "Entities");
            int shown = 0;
            for (Map.Entry<String, Map<String, Long>> e : kills.entrySet()) {
                if (shown++ >= 6) break;
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> <red>killed</red> <gray>" + top(e.getValue(), 4) + "</gray>");
            }
            if (spawns > 0) L.add("   <dark_gray>•</dark_gray> <green>" + spawns + " entities spawned</green>");
        }

        List<String> links = new ArrayList<>();
        List<Stat> linked = suspects.size() > 4 ? suspects.subList(0, 4) : suspects;
        for (int i = 0; i < linked.size(); i++) {
            for (int j = i + 1; j < linked.size(); j++) {
                long ov = overlap(intervals(linked.get(i).stamps), intervals(linked.get(j).stamps));
                if (ov >= 60_000L) {
                    links.add("<white>" + Msg.esc(linked.get(i).name) + "</white> <gray>and</gray> <white>" + Msg.esc(linked.get(j).name)
                            + "</white> <gray>were active at the same time for</gray> <white>" + Msg.ago(ov) + "</white>");
                }
            }
        }
        for (Stat s : suspects.size() > 3 ? suspects.subList(0, 3) : suspects) {
            String sessionInfo = sessionInfo(s);
            if (sessionInfo != null) links.add(sessionInfo);
        }
        if (stats.values().stream().anyMatch(s -> s.caused > 0)) {
            for (Stat s : stats.values()) {
                if (s.caused >= 5) {
                    links.add("<white>" + Msg.esc(s.name) + "</white> <gray>is linked to</gray> <white>" + s.caused + "</white> <gray>explosion-destroyed blocks"
                            + (s.causedInferred == s.caused ? " (inferred from TNT placed shortly before)" : "") + "</gray>");
                }
            }
        }
        if (!links.isEmpty()) {
            section(L, "Links");
            for (String l : links) L.add("   <dark_gray>•</dark_gray> " + l);
        }

        List<Run> runs = condense(rows);
        section(L, "Timeline");
        List<Run> shownRuns = runs;
        int hidden = 0;
        if (runs.size() > MAX_LINES) {
            List<Run> sorted = new ArrayList<>(runs);
            sorted.sort((a, b) -> Long.compare(b.weight(), a.weight()));
            Set<Run> keep = new HashSet<>(sorted.subList(0, MAX_LINES));
            shownRuns = new ArrayList<>();
            for (Run r : runs) if (keep.contains(r)) shownRuns.add(r);
            hidden = runs.size() - MAX_LINES;
        }
        for (Run r : shownRuns) L.add(line(r, true));
        if (hidden > 0) L.add("   <dark_gray>… " + hidden + " smaller events hidden. Narrow it down with r: and t: or use /up timeline <player>.</dark_gray>");
        Pager.send(out, "Investigation", List.of(), L, 1, null);
    }

    private String sessionInfo(Stat s) {
        try {
            Query jq = new Query();
            jq.users = List.of(s.name);
            jq.actions = new LinkedHashSet<>(List.of(Actions.JOIN, Actions.QUIT));
            jq.since = s.first - 6L * 3_600_000L;
            jq.until = s.last + 3L * 3_600_000L;
            List<LogRow> rows = db.selectRows(jq, true, 200, 0, null, null);
            long joinBefore = -1, quitAfter = -1;
            for (LogRow r : rows) {
                if (Actions.JOIN.equals(r.action()) && r.time() <= s.first) joinBefore = r.time();
                if (Actions.QUIT.equals(r.action()) && r.time() >= s.last && quitAfter < 0) quitAfter = r.time();
            }
            StringBuilder sb = new StringBuilder("<white>").append(Msg.esc(s.name)).append("</white> <gray>");
            boolean any = false;
            if (joinBefore >= 0) { sb.append("logged in ").append(Msg.ago(s.first - joinBefore)).append(" before the first change"); any = true; }
            if (quitAfter >= 0) {
                long d = quitAfter - s.last;
                sb.append(any ? ", " : "").append("logged out ").append(Msg.ago(d)).append(" after the last change");
                if (d <= SESSION_GAP) sb.append("</gray> <red>(left quickly)</red><gray>");
                any = true;
            } else if (any) {
                sb.append(", no logout recorded afterwards");
            }
            sb.append("</gray>");
            return any ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void timeline(Out out, String player, Map<String, String> params) {
        db.submit(() -> {
            try {
                runTimeline(out, player, params);
            } catch (Exception e) {
                log.warning("[Timeline] Failed: " + e.getMessage());
                e.printStackTrace();
                out.err("Timeline failed, see console.");
            }
        });
    }

    private static boolean involves(LogRow r, Set<String> ents) {
        String a = r.player() == null ? "" : r.player().toLowerCase(Locale.ROOT);
        String data = r.data() == null ? "" : r.data().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String by0 = Causes.by(r.meta());
        String by = by0 == null ? "" : by0.toLowerCase(Locale.ROOT);
        for (String n : ents) {
            if (a.equals("#" + n) || a.equals(n) || by.equals("#" + n) || by.equals(n) || data.equals(n) || data.contains(n)) return true;
        }
        return false;
    }

    private void runTimeline(Out out, String player, Map<String, String> params) throws Exception {
        db.flush();
        long now = System.currentTimeMillis();
        long span = params.containsKey("t") ? ParamUtils.parseTime(params.get("t")) : DAY;
        if (span <= 0) span = DAY;
        Set<String> acts = Actions.expandAll(params.get("a"));
        boolean custom = acts != null && !acts.isEmpty();
        if (!custom) acts = TIMELINE_DEFAULT;
        Query q = new Query();
        q.users = List.of(player);
        q.since = now - span;
        q.actions = acts;
        List<LogRow> own = db.selectRows(q, false, TL_CAP, 0, "type <> 'audit'", null);
        Map<Long, LogRow> merged = new LinkedHashMap<>();
        for (LogRow r : own) merged.put(r.id(), r);
        if (!player.startsWith("#") && (acts.contains(Actions.BROKE) || acts.contains(Actions.PLACED))) {
            Query q2 = new Query();
            q2.since = now - span;
            q2.actions = new LinkedHashSet<>(List.of(Actions.BROKE));
            for (LogRow r : db.selectRows(q2, false, 5000, 0, "lower(meta) = ?", List.of("by:" + player.toLowerCase(Locale.ROOT)))) merged.put(r.id(), r);
        }
        Set<String> ents = new LinkedHashSet<>();
        for (String t : params.getOrDefault("tags", "").split(",")) {
            String e = t.trim().toLowerCase(Locale.ROOT);
            if (e.length() > 1 && e.startsWith("#") && !TL_FLAGS.contains(e) && !e.equals(player.toLowerCase(Locale.ROOT))) ents.add(e.substring(1).replace("minecraft:", ""));
        }
        if (!ents.isEmpty()) merged.values().removeIf(r -> !involves(r, ents));
        if (merged.isEmpty()) { out.err("No activity found for " + player + (ents.isEmpty() ? "" : " with " + ents) + " in this period."); return; }
        List<LogRow> rows = new ArrayList<>(merged.values());
        rows.sort((a, b) -> a.time() != b.time() ? Long.compare(a.time(), b.time()) : Long.compare(a.id(), b.id()));

        int broke = 0, placed = 0, moved = 0, kills = 0, deaths = 0, tps = 0, cmds = 0, sessions = 0;
        long online = 0, joinAt = -1;
        for (LogRow r : rows) {
            switch (r.action()) {
                case Actions.BROKE -> broke++;
                case Actions.PLACED -> placed++;
                case Actions.PUT, Actions.TAKE -> moved += Math.max(1, r.amount());
                case Actions.KILL -> kills++;
                case Actions.DEATH -> deaths++;
                case Actions.TELEPORT -> tps++;
                case Actions.COMMAND -> cmds++;
                case Actions.JOIN -> { sessions++; joinAt = r.time(); }
                case Actions.QUIT -> { if (joinAt >= 0) { online += r.time() - joinAt; joinAt = -1; } }
                default -> {  }
            }
        }

        List<Run> runs = condense(rows);
        if (runs.size() > 1500) runs = new ArrayList<>(runs.subList(runs.size() - 1500, runs.size()));
        List<String> pinned = new ArrayList<>();
        pinned.add(" <gray>Last <white>" + Msg.ago(span) + "</white> <dark_gray>•</dark_gray> <white>" + rows.size() + "</white> entries <dark_gray>•</dark_gray> <white>"
                + broke + "</white> broken <dark_gray>•</dark_gray> <white>" + placed + "</white> placed <dark_gray>•</dark_gray> <white>" + moved + "</white> container items</gray>");
        pinned.add(" <gray><white>" + kills + "</white> kills <dark_gray>•</dark_gray> <white>" + deaths + "</white> deaths <dark_gray>•</dark_gray> <white>" + tps
                + "</white> teleports <dark_gray>•</dark_gray> <white>" + cmds + "</white> commands <dark_gray>•</dark_gray> <white>" + sessions + "</white> logins"
                + (online > 0 ? " <dark_gray>•</dark_gray> online <white>" + Msg.ago(online) + "</white>" : "") + "</gray>");
        List<String> L = new ArrayList<>();
        String day = "";
        for (Run r : runs) {
            String d = ts(r.first).substring(0, 10);
            if (!d.equals(day)) {
                day = d;
                L.add(" <dark_gray>—</dark_gray> <gray>" + d + "</gray> <dark_gray>—</dark_gray>");
            }
            L.add(line(r, false));
        }
        int page = Pager.LAST;
        try { if (params.containsKey("p")) page = Math.max(1, Integer.parseInt(params.get("p").trim())); }
        catch (NumberFormatException ignored) {  }
        Pager.send(out, "Timeline " + Msg.esc(player), pinned, L, page, null);
    }
}
