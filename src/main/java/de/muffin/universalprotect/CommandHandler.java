package de.muffin.universalprotect;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandHandler {
    private static final String PERM_USE = "universalprotect.use";
    private static final String PERM_ADMIN = "universalprotect.admin";
    private static final Pattern PAGE_ARG = Pattern.compile("(\\d+)(?::(\\d+))?");

    private static final List<String> KEYS_LOOKUP = List.of("u:", "t:", "r:", "a:", "i:", "e:", "f:", "p:",
            "user:", "time:", "radius:", "action:", "include:", "exclude:", "filter:", "page:");
    private static final List<String> KEYS_ROLLBACK = List.of("u:", "t:", "r:", "a:", "i:", "e:",
            "user:", "time:", "radius:", "action:", "include:", "exclude:");
    private static final List<String> KEYS_PURGE = List.of("t:", "r:", "a:", "i:", "e:", "u:",
            "time:", "radius:", "action:", "include:", "exclude:", "user:");
    private static final List<String> TAGS_LOOKUP = List.of("#count", "#summary", "#verbose", "#silent", "#command");
    private static final List<String> TAGS_ROLLBACK = List.of("#preview", "#verbose", "#silent", "#command");
    private static final List<String> TAGS_PURGE = List.of("#optimize", "#silent");
    private static final List<String> USER_SUBS = List.of("help", "inspect", "lookup", "near", "page", "stats", "status");
    private static final List<String> ADMIN_SUBS = List.of("rollback", "restore", "apply", "cancel", "undo", "purge",
            "alerts", "reload", "consumer", "migrate-db", "import-coreprotect", "compact", "investigate", "timeline", "audit");

    private static volatile List<String> MATERIALS;
    private static volatile List<String> ENTITIES;

    private final UniversalProtect plugin;

    public CommandHandler(UniversalProtect plugin) { this.plugin = plugin; }

    private static void msg(Sender s, String text) { Msg.send(s, text); }

    private boolean requireAdmin(Sender s) {
        if (s.hasPermission(PERM_ADMIN)) return true;
        Msg.err(s, "You don't have permission for this.");
        return false;
    }

    private static boolean requirePlayer(Sender s) {
        if (s.isPlayer()) return true;
        Msg.err(s, "This command can only be used by players.");
        return false;
    }

    private static String canonicalSub(String s) {
        return switch (s) {
            case "l" -> "lookup";
            case "i" -> "inspect";
            case "rb" -> "rollback";
            case "rs" -> "restore";
            case "p" -> "page";
            case "migrate", "migratedb" -> "migrate-db";
            case "inv", "incident" -> "investigate";
            case "tl" -> "timeline";
            case "import", "importcp", "cpimport" -> "import-coreprotect";
            default -> s;
        };
    }

    private boolean validate(Sender s, Map<String, String> params, boolean requireTime) {
        if (params.containsKey("t") && ParamUtils.parseTime(params.get("t")) < 0) {
            Msg.err(s, "Invalid time. Use e.g. t:30m, t:2h, t:1d, t:1w or t:1d12h");
            return false;
        }
        if (requireTime && !params.containsKey("t")) {
            Msg.err(s, "This action requires a time parameter (e.g. t:1h)");
            return false;
        }
        String r = params.get("r");
        if (r != null && !r.startsWith("#")) {
            try {
                if (Integer.parseInt(r.trim()) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                Msg.err(s, "Invalid radius. Use r:10 or r:#global / r:#world_name");
                return false;
            }
            if (!s.isPlayer()) {
                Msg.err(s, "From the console use r:#global or r:#<world> instead of a numeric radius.");
                return false;
            }
        }
        if (r != null && r.startsWith("#")) {
            for (String w : r.split(",")) {
                String name = w.replace("#", "").trim();
                if (name.isEmpty() || name.equalsIgnoreCase("global")) continue;
                if (Mc.level(name) == null) {
                    Msg.err(s, "World <white>" + Msg.esc(name) + "</white> is not loaded.");
                    return false;
                }
            }
        }
        String a = params.get("a");
        if (a != null) {
            Set<String> acts = Actions.expandAll(a);
            if (acts != null) {
                for (String act : acts) {
                    if (!Actions.ALL.contains(act)) {
                        Msg.err(s, "Unknown action <white>" + Msg.esc(act) + "</white>. Try a:block, a:container, a:inventory, a:item, a:kill, a:spawn, a:chat, a:command, a:sign, a:session, a:username");
                        return false;
                    }
                }
            }
        }
        if (params.containsKey("f")) {
            Set<String> acts = Actions.expandAll(a);
            boolean ok = acts != null && !acts.isEmpty() && Actions.TEXT.containsAll(acts);
            if (!ok) {
                Msg.err(s, "The filter parameter (f:) can only be combined with a:chat, a:command or a:sign.");
                return false;
            }
        }
        return true;
    }

    private Query lookupQuery(Sender s, Map<String, String> p, String defaultRadius, String type) {
        return Query.from(s, p, defaultRadius, type, plugin.settings().defaultLines(), plugin.settings().maxLines());
    }

    public void run(Sender sender, String[] args) {
        if (!sender.hasPermission(PERM_USE)) { Msg.err(sender, "You don't have permission."); return; }
        if (args.length == 0) { sendHelp(sender); return; }
        DatabaseManager db = plugin.db();
        String sub = canonicalSub(args[0].toLowerCase(Locale.ROOT));
        auditCommand(sender, sub, args);

        switch (sub) {
            case "help" -> sendHelp(sender);
            case "status" -> {
                Msg.header(sender, "Status");
                Msg.kv(sender, "Version", plugin.version());
                Msg.kv(sender, "Database", db.type().id() + (db.isMigrating() ? " <gray>(migrating)</gray>" : ""));
                Msg.kv(sender, "Logging", plugin.isConsumerActive() ? "<green>Active</green>" : "<red>Paused</red>");
                Msg.footer(sender);
            }
            case "stats" -> db.stats(sender);
            case "consumer" -> {
                if (!requireAdmin(sender)) return;
                plugin.setConsumerActive(!plugin.isConsumerActive());
                msg(sender, "Logging: " + (plugin.isConsumerActive() ? "<green>Resumed</green>" : "<red>Paused</red>"));
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return;
                plugin.reload();
                Msg.ok(sender, "Configuration, per-world files and blacklist reloaded.");
            }
            case "alerts" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) return;
                if (plugin.getAlertPlayers().remove(sender.key())) { plugin.getAlertMuted().add(sender.key()); msg(sender, "Alerts <red>disabled</red>."); }
                else { plugin.getAlertMuted().remove(sender.key()); plugin.getAlertPlayers().add(sender.key()); msg(sender, "Alerts <green>enabled</green> (anomaly and ore alerts, see config.yml)."); }
            }
            case "undo" -> { if (requireAdmin(sender)) plugin.rollback().undo(sender); }
            case "apply" -> { if (requireAdmin(sender)) plugin.rollback().apply(sender); }
            case "cancel" -> { if (requireAdmin(sender)) plugin.rollback().cancel(sender); }
            case "tp" -> {
                if (args.length < 5 || !requirePlayer(sender)) return;
                try {
                    String dim = Mc.normalizeWorld(args[1]);
                    if (Mc.level(dim) == null) { Msg.err(sender, "World not found."); return; }
                    int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]), z = Integer.parseInt(args[4]);
                    String cmd = "execute in " + dim + " as " + sender.player().getUUID() + " run tp @s " + (x + 0.5) + " " + y + " " + (z + 0.5);
                    Mc.server().getCommands().performPrefixedCommand(Mc.server().createCommandSourceStack(), cmd);
                } catch (NumberFormatException ignored) {  }
            }
            case "inspect" -> {
                if (!requirePlayer(sender)) return;
                if (plugin.getInspectingPlayers().remove(sender.key())) msg(sender, "Inspector <red>disabled</red>.");
                else { plugin.getInspectingPlayers().add(sender.key()); msg(sender, "Inspector <green>enabled</green>. Left-click: block, right-click: placed position."); }
            }
            case "page" -> {
                Query last = db.getLastQuery(sender);
                if (last == null) { Msg.err(sender, "No previous lookup. Use /up lookup first."); return; }
                int pg = last.page + 1, lines = last.lines;
                if (args.length > 1) {
                    Matcher m = PAGE_ARG.matcher(args[1]);
                    if (!m.matches()) { Msg.err(sender, "Usage: /up page [number[:lines]]"); return; }
                    pg = Math.max(1, Integer.parseInt(m.group(1)));
                    if (m.group(2) != null) lines = Math.max(1, Math.min(plugin.settings().maxLines(), Integer.parseInt(m.group(2))));
                }
                db.lookup(sender, last.withPage(pg, lines));
            }
            case "near" -> {
                if (!requirePlayer(sender)) return;
                Map<String, String> p = new HashMap<>(ParamUtils.parseArgs(args));
                p.putIfAbsent("r", "5");
                p.putIfAbsent("a", "inspect");
                if (!validate(sender, p, false)) return;
                db.lookup(sender, lookupQuery(sender, p, "5", null));
            }
            case "lookup" -> {
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (!validate(sender, p, false)) return;
                db.lookup(sender, lookupQuery(sender, p, "#global", null));
            }
            case "rollback", "restore" -> {
                if (!requireAdmin(sender)) return;
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (!validate(sender, p, true)) return;
                if (!sender.isPlayer() && !p.containsKey("r")) {
                    Msg.err(sender, "From the console you must specify the area, e.g. r:#world or r:#global.");
                    return;
                }
                plugin.rollback().start(sender, lookupQuery(sender, p, p.getOrDefault("tags", "").contains("#command") ? "#global" : "10", null), sub.equals("restore"));
            }
            case "purge" -> {
                if (!requireAdmin(sender)) return;
                purge(sender, args);
            }
            case "compact" -> {
                if (!requireAdmin(sender)) return;
                Map<String, String> p = ParamUtils.parseArgs(args);
                long span = p.containsKey("t") ? ParamUtils.parseTime(p.get("t")) : -1;
                if (span < 7L * 86_400_000L) {
                    Msg.err(sender, "Usage: /up compact t:<time> [#dry]  (keeps at least the last 7 days in full detail, so t:7d or more)");
                    return;
                }
                boolean dry = p.getOrDefault("tags", "").contains("#dry");
                plugin.compactor().start(reporter(sender), span, dry);
            }
            case "import-coreprotect" -> {
                if (!requireAdmin(sender)) return;
                if (sender.isPlayer()) { Msg.err(sender, "The CoreProtect import can only be started from the console."); return; }
                plugin.importer().start(reporter(sender), args);
            }
            case "migrate-db" -> {
                if (!requireAdmin(sender)) return;
                if (sender.isPlayer()) { Msg.err(sender, "The database migration can only be started from the console."); return; }
                DbType target = args.length > 1 ? DbType.parse(args[1]) : null;
                if (target == null) { Msg.err(sender, "Usage: /up migrate-db <sqlite|mysql|duckdb|clickhouse> [--full-validation]"); return; }
                boolean full = ParamUtils.parseArgs(args).getOrDefault("tags", "").contains("#full-validation");
                plugin.migrator().start(sender, target, full);
            }
            case "view" -> {
                int pg = 1;
                try { if (args.length > 1) pg = Integer.parseInt(args[1].trim()); } catch (NumberFormatException ignored) {  }
                Pager.show(outOf(sender), pg);
            }
            case "investigate" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) return;
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (p.containsKey("t") && ParamUtils.parseTime(p.get("t")) < 0) { Msg.err(sender, "Invalid time. Use e.g. t:30m, t:2h, t:1d"); return; }
        net.minecraft.server.level.ServerPlayer pl = sender.player();
        Investigator.Spot spot = new Investigator.Spot(Mc.key(pl.level()), pl.blockPosition().getX(), pl.blockPosition().getY(), pl.blockPosition().getZ());
                plugin.investigator().investigate(outOf(sender), spot, p);
            }
            case "timeline" -> {
                if (!requireAdmin(sender)) return;
                Map<String, String> p = ParamUtils.parseArgs(args);
                String who = args.length > 1 && !args[1].contains(":") && !args[1].matches("\\d+") ? args[1] : p.get("u");
                if (who == null) { Msg.err(sender, "Usage: /up timeline <player|#entity> [t:<time>] [a:<actions>] [p:<page>]"); return; }
                if (!validate(sender, p, false)) return;
                plugin.investigator().timeline(outOf(sender), who, p);
            }
            case "audit" -> {
                if (!requireAdmin(sender)) return;
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (p.containsKey("t") && ParamUtils.parseTime(p.get("t")) < 0) { Msg.err(sender, "Invalid time. Use e.g. t:1d, t:7d"); return; }
                plugin.audit().view(outOf(sender), p);
            }
            default -> sendHelp(sender);
        }
    }

    private void purge(Sender sender, String[] args) {
        Map<String, String> p = ParamUtils.parseArgs(args);
        Set<String> tags = new HashSet<>(Arrays.asList(p.getOrDefault("tags", "").split(",")));
        boolean optimize = tags.contains("#optimize");
        boolean silent = tags.contains("#silent");
        boolean hasTime = p.containsKey("t");
        if (!hasTime && !optimize) {
            Msg.err(sender, "Usage: /up purge t:<time> [r:<radius|#world>] [i:<block>] [a:kill e:<entity>] [#optimize]  —  or just /up purge #optimize");
            return;
        }
        if (!validate(sender, p, hasTime)) return;
        Query q = lookupQuery(sender, p, "#global", null);
        q.since = 0;
        if (hasTime) {
            long time = ParamUtils.parseTime(p.get("t"));
            if (time < 86_400_000L) { Msg.err(sender, "Purge must keep at least 24 hours (t:1d or more)."); return; }
            q.until = System.currentTimeMillis() - time;
        } else {
            q.until = 0;
        }
        if (q.actions != null && (q.actions.contains(Actions.KILL) || q.actions.contains(Actions.SPAWN))
                && q.include.isEmpty() && !q.exclude.isEmpty()) {
            q.include = q.exclude;
            q.exclude = List.of();
        }
        plugin.db().purge(sender, q, optimize, silent);
    }

    private void sendHelp(Sender p) {
        List<String> L = new ArrayList<>();
        L.add(helpLine("/up inspect (i)", "Toggle the inspector"));
        L.add(helpLine("/up lookup (l) <params>", "Search the log, e.g. /up l u:Steve t:1d a:+block r:20"));
        L.add(helpLine("/up lookup 2:10", "Page 2 with 10 lines per page"));
        L.add(helpLine("/up near", "Lookup within radius 5"));
        L.add(helpLine("/up page [n[:lines]]", "Other page of the last lookup"));
        L.add(helpLine("/up stats | status", "Database statistics / status"));
        if (p.hasPermission(PERM_ADMIN)) {
            L.add(helpLine("/up rollback (rb) <params>", "Roll back (needs t:), #preview first, #command = /fill etc."));
            L.add(helpLine("/up restore (rs) <params>", "Re-apply rolled back changes"));
            L.add(helpLine("/up apply | cancel", "Confirm or discard a #preview"));
            L.add(helpLine("/up undo", "Revert the last rollback/restore"));
            L.add(helpLine("/up purge t:30d [#optimize]", "Delete old data (r:, i:, a:kill e: possible)"));
            L.add(helpLine("/up migrate-db <type> [--full-validation]", "Console: migrate to sqlite/mysql/duckdb/clickhouse"));
            L.add(helpLine("/up import-coreprotect sqlite|mysql ...", "Console: import a CoreProtect database"));
            L.add(helpLine("/up compact t:30d [#dry]", "Summarise old block rows per player/chunk/day (a:agg)"));
            L.add(helpLine("/up investigate [r:15] [t:2h]", "Incident report for the area around you (suspects, links, timeline)"));
            L.add(helpLine("/up timeline <player|#entity> [t:24h] [a:...]", "Chronological activity of a player"));
            L.add(helpLine("/up audit [t:7d] [u:<staff>]", "Staff action log (rollbacks, purges, settings, investigations)"));
            L.add(helpLine("/up alerts | reload | consumer", "Ore and anomaly alerts / reload config / pause logging"));
        }
        L.add("");
        L.add(" <gray>Params:</gray> <white>u:|user:  t:|time:  r:|radius:  a:|action:  i:|include:  e:|exclude:  f:|filter:  p:|page:</white>");
        L.add(" <gray>Tags:</gray> <white>#preview #count #summary #verbose #silent #optimize</white>");
        L.add(" <gray>Actions:</gray> <white>block +block -block chat click command sign container inventory item kill spawn session username</white>");
        Pager.send(outOf(p), "Help", List.of(), L, 1, null);
    }

    private static CoreProtectImporter.Reporter reporter(Sender s) {
        return new CoreProtectImporter.Reporter() {
            @Override public void info(String m) { Msg.info(s, Msg.esc(m)); }
            @Override public void ok(String m) { Msg.ok(s, m); }
            @Override public void err(String m) { Msg.err(s, Msg.esc(m)); }
        };
    }

    private static final Set<String> AUDITED = Set.of("rollback", "restore", "apply", "undo", "purge", "compact", "import-coreprotect", "migrate-db", "reload", "consumer");

    private static Investigator.Out outOf(Sender s) {
        return new Investigator.Out() {
            @Override public void raw(String mini) { Msg.raw(s, mini); }
            @Override public void err(String text) { Msg.err(s, Msg.esc(text)); }
            @Override public String key() { return s.key().toString(); }
        };
    }

    private void auditCommand(Sender sender, String sub, String[] args) {
        try {
            boolean audited = false;
            if (AUDITED.contains(sub)) {
                audited = sender.hasPermission(PERM_ADMIN);
            } else if (sub.equals("investigate")) {
                audited = sender.hasPermission(PERM_ADMIN);
            } else if (sub.equals("lookup") || sub.equals("near") || sub.equals("timeline")) {
                Map<String, String> p = ParamUtils.parseArgs(args);
                String target = sub.equals("timeline") && args.length > 1 && !args[1].contains(":") && !args[1].startsWith("#") ? args[1] : p.get("u");
                audited = target != null && !target.equalsIgnoreCase(sender.name()) && sender.hasPermission(PERM_USE);
            }
            if (!audited) return;
        net.minecraft.server.level.ServerPlayer pl = sender.player();
        String world = pl == null ? "" : Mc.key(pl.level());
        int x = pl == null ? 0 : pl.blockPosition().getX(), y = pl == null ? 0 : pl.blockPosition().getY(), z = pl == null ? 0 : pl.blockPosition().getZ();
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < args.length; i++) rest.append(args[i]).append(' ');
            plugin.audit().record(sender.name(), sender.isPlayer() ? sender.key().toString() : null, world, x, y, z, sub, rest.toString());
            plugin.discord().audit(sender.name(), sub, rest.toString(), world, x, y, z);
        } catch (RuntimeException ignored) {  }
    }

    private static String helpLine(String cmd, String desc) {
        return " <green>" + Msg.esc(cmd) + "</green> <dark_gray>»</dark_gray> <gray>" + Msg.esc(desc) + "</gray>";
    }

    private static void help(Sender to, String cmd, String desc) {
        Msg.raw(to, " <green>" + Msg.esc(cmd) + "</green> <dark_gray>»</dark_gray> <gray>" + Msg.esc(desc) + "</gray>");
    }

    private static List<String> materials() {
        List<String> m = MATERIALS;
        if (m == null) {
            List<String> l = new ArrayList<>();
            for (net.minecraft.world.item.Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                l.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).getPath());
            }
            Collections.sort(l);
            MATERIALS = m = l;
        }
        return m;
    }

    private static List<String> entities() {
        List<String> m = ENTITIES;
        if (m == null) {
            List<String> l = new ArrayList<>();
            for (net.minecraft.world.entity.EntityType<?> t : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) l.add(Mc.entityPath(t));
            Collections.sort(l);
            ENTITIES = m = l;
        }
        return m;
    }

    private List<String> userOptions() {
        Set<String> s = new LinkedHashSet<>();
        for (net.minecraft.server.level.ServerPlayer p : Mc.players()) s.add(Mc.name(p));
        s.addAll(plugin.db().getKnownUsers());
        return new ArrayList<>(s);
    }

    private List<String> valueOptions(String key, String sub) {
        return switch (key) {
            case "u:" -> userOptions();
            case "r:" -> {
                List<String> l = new ArrayList<>(List.of("5", "10", "25", "50", "100", "#global"));
                for (String w : Mc.worldKeys()) l.add("#" + w.replace("minecraft:", ""));
                yield l;
            }
            case "a:" -> (sub.equals("rollback") || sub.equals("restore"))
                    ? List.of("+block", "-block", "block", "container", "+container", "-container", "inventory", "+inventory", "-inventory", "kill", "spawn")
                    : Actions.USER_NAMES;
            case "i:", "e:" -> {
                List<String> l = new ArrayList<>(materials());
                l.addAll(entities());
                yield l;
            }
            case "p:" -> List.of("1", "2", "3", "4", "5", "1:10", "1:25");
            default -> List.of();
        };
    }

    private static String canonicalWithColon(String key) { return ParamUtils.canonicalKey(key.replace(":", "")) + ":"; }

    public List<String> complete(Sender sender, String[] args) {
        if (!sender.hasPermission(PERM_USE)) return List.of();
        String last = args[args.length - 1];
        String lastLower = last.toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(USER_SUBS);
            if (sender.hasPermission(PERM_ADMIN)) subs.addAll(ADMIN_SUBS);
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(lastLower)) out.add(s);
            return out;
        }

        String sub = canonicalSub(args[0].toLowerCase(Locale.ROOT));
        if (sub.equals("import-coreprotect") && sender.hasPermission(PERM_ADMIN)) {
            List<String> out = new ArrayList<>();
            if (args.length == 2) { for (String n : List.of("sqlite", "mysql")) if (n.startsWith(lastLower)) out.add(n); }
            else for (String n : List.of("--items", "--force", "--prefix=co_")) if (n.startsWith(lastLower)) out.add(n);
            return out;
        }
        if (sub.equals("migrate-db") && sender.hasPermission(PERM_ADMIN)) {
            List<String> out = new ArrayList<>();
            if (args.length == 2) { for (String n : DbType.NAMES) if (n.startsWith(lastLower)) out.add(n); }
            else if ("--full-validation".startsWith(lastLower)) out.add("--full-validation");
            return out;
        }

        if (sub.equals("timeline") && args.length == 2 && sender.hasPermission(PERM_ADMIN)) {
            List<String> names = new ArrayList<>();
            for (String n : userOptions()) if (n.toLowerCase(Locale.ROOT).startsWith(lastLower)) names.add(n);
            return names;
        }
        List<String> keys, tags;
        switch (sub) {
            case "lookup", "near" -> { keys = KEYS_LOOKUP; tags = TAGS_LOOKUP; }
            case "rollback", "restore" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = KEYS_ROLLBACK; tags = TAGS_ROLLBACK;
            }
            case "investigate" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = List.of("r:", "t:", "u:"); tags = List.of();
            }
            case "timeline" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = List.of("t:", "a:", "p:"); tags = List.of();
            }
            case "audit" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = List.of("t:", "u:", "p:"); tags = List.of();
            }
            case "compact" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = List.of("t:"); tags = List.of("#dry");
            }
            case "purge" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = KEYS_PURGE; tags = TAGS_PURGE;
            }
            default -> { return List.of(); }
        }

        Set<String> usedKeys = new HashSet<>();
        Set<String> usedTags = new HashSet<>();
        for (int i = 1; i < args.length - 1; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.startsWith("#")) { usedTags.add(a); continue; }
            int idx = a.indexOf(':');
            if (idx > 0) usedKeys.add(canonicalWithColon(a.substring(0, idx + 1)));
        }

        int colon = last.indexOf(':');
        List<String> out = new ArrayList<>();

        if (colon < 0) {
            for (String k : keys) if (!usedKeys.contains(canonicalWithColon(k)) && k.startsWith(lastLower)) out.add(k);
            for (String t : tags) if (!usedTags.contains(t) && t.startsWith(lastLower)) out.add(t);
            return out;
        }

        String rawKey = last.substring(0, colon + 1).toLowerCase(Locale.ROOT);
        String key = canonicalWithColon(rawKey);
        String val = last.substring(colon + 1);
        if (!keys.contains(rawKey)) return List.of();

        if (key.equals("t:")) {
            if (val.matches("(\\d+(\\.\\d+)?[smhdw])*\\d+(\\.\\d+)?")) {
                for (String u : List.of("s", "m", "h", "d", "w")) out.add(last + u);
            } else {
                for (String d : List.of("30m", "1h", "6h", "12h", "1d", "3d", "1w"))
                    if (d.startsWith(val.toLowerCase(Locale.ROOT))) out.add(last.substring(0, colon + 1) + d);
            }
            return out;
        }

        boolean multi = key.equals("u:") || key.equals("r:") || key.equals("a:") || key.equals("i:") || key.equals("e:");
        int comma = multi ? val.lastIndexOf(',') : -1;
        String head = last.substring(0, colon + 1 + comma + 1);
        String partial = val.substring(comma + 1).toLowerCase(Locale.ROOT);

        Set<String> chosen = new HashSet<>();
        if (comma >= 0) for (String c : val.substring(0, comma + 1).split(",")) chosen.add(c.trim().toLowerCase(Locale.ROOT));

        for (String opt : valueOptions(key, sub)) {
            String o = opt.toLowerCase(Locale.ROOT);
            if (!o.startsWith(partial) || chosen.contains(o)) continue;
            out.add(head + opt);
            if (out.size() >= 50) break;
        }
        return out;
    }
}
