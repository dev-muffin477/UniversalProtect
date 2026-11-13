package de.muffin.universalprotect;

import java.util.*;

public final class Query {
    public String type;
    public List<String> users = List.of();
    public List<String> excludeUsers = List.of();
    public Set<String> actions;
    public String rawAction;
    public long since;
    public long until;
    public int radius = -1;
    public List<String> worlds = List.of();
    public int x, y, z;
    public int page = 1;
    public int lines = 15;
    public List<String> include = List.of();
    public List<String> exclude = List.of();
    public List<String> filterInclude = List.of();
    public List<String> filterExclude = List.of();
    public Set<String> tags = Set.of();

    public boolean hasTag(String t) { return tags.contains(t); }

    public Query copy() {
        Query q = new Query();
        q.type = type; q.users = users; q.excludeUsers = excludeUsers; q.actions = actions; q.rawAction = rawAction;
        q.since = since; q.until = until; q.radius = radius; q.worlds = worlds; q.x = x; q.y = y; q.z = z;
        q.page = page; q.lines = lines; q.include = include; q.exclude = exclude;
        q.filterInclude = filterInclude; q.filterExclude = filterExclude; q.tags = tags;
        return q;
    }

    public Query withPage(int p) { Query q = copy(); q.page = p; return q; }

    public Query withPage(int p, int l) { Query q = copy(); q.page = p; q.lines = l; return q; }

    public static Query from(Sender sender, Map<String, String> params, String defaultRadius, String type, int defaultLines, int maxLines) {
        Query q = new Query();
        q.type = type;
        if (params.containsKey("t")) {
            long d = ParamUtils.parseTime(params.get("t"));
            if (d >= 0) q.since = System.currentTimeMillis() - d;
        }

        net.minecraft.server.level.ServerPlayer pl = sender.player();
        String radStr = params.getOrDefault("r", defaultRadius);
        List<String> worlds = new ArrayList<>();
        if (radStr != null) {
            if (radStr.startsWith("#")) {
                if (!radStr.equalsIgnoreCase("#global")) {
                    for (String s : radStr.split(",")) {
                        String w = s.replace("#", "").trim();
                        if (w.isEmpty()) continue;
                        net.minecraft.server.level.ServerLevel bw = Mc.level(w);
                        worlds.add(bw != null ? Mc.key(bw) : Mc.normalizeWorld(w));
                    }
                }
            } else if (pl != null) {
                try {
                    q.radius = Integer.parseInt(radStr.trim());
                    worlds.add(Mc.key(pl.level()));
                } catch (NumberFormatException ignored) {  }
            }
        }
        q.worlds = worlds;
        if (pl != null) { q.x = pl.blockPosition().getX(); q.y = pl.blockPosition().getY(); q.z = pl.blockPosition().getZ(); }

        try { if (params.containsKey("p")) q.page = Math.max(1, Integer.parseInt(params.get("p").trim())); }
        catch (NumberFormatException ignored) { q.page = 1; }
        q.lines = defaultLines;
        try { if (params.containsKey("lines")) q.lines = Integer.parseInt(params.get("lines").trim()); }
        catch (NumberFormatException ignored) { q.lines = defaultLines; }
        q.lines = Math.max(1, Math.min(maxLines, q.lines));

        q.users = ParamUtils.split(params.get("u"));
        q.rawAction = params.get("a");
        q.actions = Actions.expandAll(q.rawAction);
        q.include = ParamUtils.split(params.get("i"));
        q.exclude = ParamUtils.split(params.get("e"));

        List<String> fi = new ArrayList<>(), fe = new ArrayList<>();
        for (String f : ParamUtils.split(params.get("f"))) {
            if (f.startsWith("-") && f.length() > 1) fe.add(f.substring(1)); else fi.add(f);
        }
        q.filterInclude = fi;
        q.filterExclude = fe;

        Set<String> tags = new LinkedHashSet<>();
        String raw = params.get("tags");
        if (raw != null) for (String t : raw.split(",")) if (t.startsWith("#") && t.length() > 1) tags.add(t.substring(1));
        q.tags = tags;
        return q;
    }

    public static Query at(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos b, int lines) {
        Query q = new Query();
        q.actions = Actions.expand("inspect");
        q.rawAction = "inspect";
        q.worlds = List.of(Mc.key(level));
        q.x = b.getX(); q.y = b.getY(); q.z = b.getZ();
        q.radius = 0;
        q.lines = lines;
        return q;
    }
}
