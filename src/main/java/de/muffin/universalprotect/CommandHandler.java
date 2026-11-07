package de.muffin.universalprotect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandHandler implements CommandExecutor, TabCompleter {
    private static final String PERM_USE = "universalprotect.use";
    private static final String PERM_ADMIN = "universalprotect.admin";
    private static final Pattern PAGE_ARG = Pattern.compile("(\\d+)(?::(\\d+))?");

    private static final List<String> KEYS_LOOKUP = List.of("u:", "t:", "r:", "a:", "i:", "e:", "f:", "p:",
            "user:", "time:", "radius:", "action:", "include:", "exclude:", "filter:", "page:");
    private static final List<String> KEYS_ROLLBACK = List.of("u:", "t:", "r:", "a:", "i:", "e:",
            "user:", "time:", "radius:", "action:", "include:", "exclude:");
    private static final List<String> KEYS_PURGE = List.of("t:", "r:", "a:", "i:", "e:", "u:",
            "time:", "radius:", "action:", "include:", "exclude:", "user:");
    private static final List<String> TAGS_LOOKUP = List.of("#count", "#summary", "#verbose", "#silent");
    private static final List<String> TAGS_ROLLBACK = List.of("#preview", "#verbose", "#silent");
    private static final List<String> TAGS_PURGE = List.of("#optimize", "#silent");
    private static final List<String> USER_SUBS = List.of("help", "inspect", "lookup", "near", "page", "stats", "status");
    private static final List<String> ADMIN_SUBS = List.of("rollback", "restore", "apply", "cancel", "undo", "purge",
            "alerts", "reload", "consumer", "migrate-db");

    private static volatile List<String> MATERIALS;
    private static volatile List<String> ENTITIES;

    private final UniversalProtect plugin;

    public CommandHandler(UniversalProtect plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private static void msg(CommandSender s, String text) { Msg.send(s, text); }

    private boolean requireAdmin(CommandSender s) {
        if (s.hasPermission(PERM_ADMIN)) return true;
        Msg.err(s, "You don't have permission for this.");
        return false;
    }

    private static boolean requirePlayer(CommandSender s) {
        if (s instanceof Player) return true;
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
            default -> s;
        };
    }

    private boolean validate(CommandSender s, Map<String, String> params, boolean requireTime) {
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
            if (!(s instanceof Player)) {
                Msg.err(s, "From the console use r:#global or r:#<world> instead of a numeric radius.");
                return false;
            }
        }
        if (r != null && r.startsWith("#")) {
            for (String w : r.split(",")) {
                String name = w.replace("#", "").trim();
                if (name.isEmpty() || name.equalsIgnoreCase("global")) continue;
                if (Bukkit.getWorld(name) == null) {
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

    private Query lookupQuery(CommandSender s, Map<String, String> p, String defaultRadius, String type) {
        return Query.from(s, p, defaultRadius, type, plugin.settings().defaultLines(), plugin.settings().maxLines());
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_USE)) { Msg.err(sender, "You don't have permission."); return true; }
        if (args.length == 0) { sendHelp(sender); return true; }
        DatabaseManager db = plugin.db();
        String sub = canonicalSub(args[0].toLowerCase(Locale.ROOT));

        switch (sub) {
            case "help" -> sendHelp(sender);
            case "status" -> {
                Msg.header(sender, "Status");
                Msg.kv(sender, "Version", plugin.getPluginMeta().getVersion());
                Msg.kv(sender, "Database", db.type().id() + (db.isMigrating() ? " <gray>(migrating)</gray>" : ""));
                Msg.kv(sender, "Logging", plugin.isConsumerActive() ? "<green>Active</green>" : "<red>Paused</red>");
                Msg.footer(sender);
            }
            case "stats" -> db.stats(sender);
            case "consumer" -> {
                if (!requireAdmin(sender)) return true;
                plugin.setConsumerActive(!plugin.isConsumerActive());
                msg(sender, "Logging: " + (plugin.isConsumerActive() ? "<green>Resumed</green>" : "<red>Paused</red>"));
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                plugin.reload();
                Msg.ok(sender, "Configuration, per-world files and blacklist reloaded.");
            }
            case "alerts" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) return true;
                Player p = (Player) sender;
                if (plugin.getAlertPlayers().remove(p.getUniqueId())) msg(p, "Ore alerts <red>disabled</red>.");
                else { plugin.getAlertPlayers().add(p.getUniqueId()); msg(p, "Ore alerts <green>enabled</green>. Configure blocks in config.yml."); }
            }
            case "undo" -> { if (requireAdmin(sender)) plugin.rollback().undo(sender); }
            case "apply" -> { if (requireAdmin(sender)) plugin.rollback().apply(sender); }
            case "cancel" -> { if (requireAdmin(sender)) plugin.rollback().cancel(sender); }
            case "tp" -> {
                if (args.length < 5 || !requirePlayer(sender)) return true;
                try {
                    World w = Bukkit.getWorld(args[1]);
                    if (w == null) { Msg.err(sender, "World not found."); return true; }
                    ((Player) sender).teleportAsync(new Location(w, Integer.parseInt(args[2]) + 0.5, Integer.parseInt(args[3]), Integer.parseInt(args[4]) + 0.5));
                } catch (NumberFormatException ignored) { /* ungültige Koordinaten */ }
            }
            case "inspect" -> {
                if (!requirePlayer(sender)) return true;
                Player p = (Player) sender;
                if (plugin.getInspectingPlayers().remove(p.getUniqueId())) msg(p, "Inspector <red>disabled</red>.");
                else { plugin.getInspectingPlayers().add(p.getUniqueId()); msg(p, "Inspector <green>enabled</green>. Left-click: block, right-click: placed position."); }
            }
            case "page" -> {
                Query last = db.getLastQuery(sender);
                if (last == null) { Msg.err(sender, "No previous lookup. Use /up lookup first."); return true; }
                int pg = last.page + 1, lines = last.lines;
                if (args.length > 1) {
                    Matcher m = PAGE_ARG.matcher(args[1]);
                    if (!m.matches()) { Msg.err(sender, "Usage: /up page [number[:lines]]"); return true; }
                    pg = Math.max(1, Integer.parseInt(m.group(1)));
                    if (m.group(2) != null) lines = Math.max(1, Math.min(plugin.settings().maxLines(), Integer.parseInt(m.group(2))));
                }
                db.lookup(sender, last.withPage(pg, lines));
            }
            case "near" -> {
                if (!requirePlayer(sender)) return true;
                Map<String, String> p = new HashMap<>(ParamUtils.parseArgs(args));
                p.putIfAbsent("r", "5");
                p.putIfAbsent("a", "inspect");
                if (!validate(sender, p, false)) return true;
                db.lookup(sender, lookupQuery(sender, p, "5", null));
            }
            case "lookup" -> {
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (!validate(sender, p, false)) return true;
                db.lookup(sender, lookupQuery(sender, p, "#global", null));
            }
            case "rollback", "restore" -> {
                if (!requireAdmin(sender)) return true;
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (!validate(sender, p, true)) return true;
                if (!(sender instanceof Player) && !p.containsKey("r")) {
                    Msg.err(sender, "From the console you must specify the area, e.g. r:#world or r:#global.");
                    return true;
                }
                plugin.rollback().start(sender, lookupQuery(sender, p, "10", null), sub.equals("restore"));
            }
            case "purge" -> {
                if (!requireAdmin(sender)) return true;
                purge(sender, args);
            }
            case "migrate-db" -> {
                if (!requireAdmin(sender)) return true;
                if (sender instanceof Player) { Msg.err(sender, "The database migration can only be started from the console."); return true; }
                DbType target = args.length > 1 ? DbType.parse(args[1]) : null;
                if (target == null) { Msg.err(sender, "Usage: /up migrate-db <sqlite|mysql|duckdb|clickhouse> [--full-validation]"); return true; }
                boolean full = ParamUtils.parseArgs(args).getOrDefault("tags", "").contains("#full-validation");
                plugin.migrator().start(sender, target, full);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void purge(CommandSender sender, String[] args) {
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
        // Gezielter Entity-Purge: a:kill e:villager löscht nur Villager-Kills
        if (q.actions != null && (q.actions.contains(Actions.KILL) || q.actions.contains(Actions.SPAWN))
                && q.include.isEmpty() && !q.exclude.isEmpty()) {
            q.include = q.exclude;
            q.exclude = List.of();
        }
        plugin.db().purge(sender, q, optimize, silent);
    }

    private void sendHelp(CommandSender p) {
        Msg.header(p, "Help");
        help(p, "/up inspect (i)", "Toggle the inspector");
        help(p, "/up lookup (l) <params>", "Search the log, e.g. /up l u:Steve t:1d a:+block r:20");
        help(p, "/up lookup 2:10", "Page 2 with 10 lines per page");
        help(p, "/up near", "Lookup within radius 5");
        help(p, "/up page [n[:lines]]", "Other page of the last lookup");
        help(p, "/up stats | status", "Database statistics / status");
        if (p.hasPermission(PERM_ADMIN)) {
            help(p, "/up rollback (rb) <params>", "Roll back (needs t:), add #preview first");
            help(p, "/up restore (rs) <params>", "Re-apply rolled back changes");
            help(p, "/up apply | cancel", "Confirm or discard a #preview");
            help(p, "/up undo", "Revert the last rollback/restore");
            help(p, "/up purge t:30d [#optimize]", "Delete old data (r:, i:, a:kill e: possible)");
            help(p, "/up migrate-db <type> [--full-validation]", "Console: migrate to sqlite/mysql/duckdb/clickhouse");
            help(p, "/up alerts | reload | consumer", "Ore alerts / reload config / pause logging");
        }
        Msg.raw(p, "");
        Msg.raw(p, " <gray>Params:</gray> <white>u:|user:  t:|time:  r:|radius:  a:|action:  i:|include:  e:|exclude:  f:|filter:  p:|page:</white>");
        Msg.raw(p, " <gray>Tags:</gray> <white>#preview #count #summary #verbose #silent #optimize</white>");
        Msg.raw(p, " <gray>Actions:</gray> <white>block +block -block chat click command sign container inventory item kill spawn session username</white>");
        Msg.footer(p);
    }

    private static void help(CommandSender to, String cmd, String desc) {
        Msg.raw(to, " <green>" + Msg.esc(cmd) + "</green> <dark_gray>»</dark_gray> <gray>" + Msg.esc(desc) + "</gray>");
    }

    // ------------------------------------------------------------------ Tab-Completion

    private static List<String> materials() {
        List<String> m = MATERIALS;
        if (m == null) {
            List<String> l = new ArrayList<>();
            for (Material mat : Material.values()) {
                if (mat.name().startsWith("LEGACY_") || mat.isAir()) continue;
                l.add(mat.name().toLowerCase(Locale.ROOT));
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
            for (EntityType t : EntityType.values()) if (t != EntityType.UNKNOWN) l.add(t.name().toLowerCase(Locale.ROOT));
            Collections.sort(l);
            ENTITIES = m = l;
        }
        return m;
    }

    private List<String> userOptions() {
        Set<String> s = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) s.add(p.getName());
        s.addAll(plugin.db().getKnownUsers());
        return new ArrayList<>(s);
    }

    private List<String> valueOptions(String key, String sub) {
        return switch (key) {
            case "u:" -> userOptions();
            case "r:" -> {
                List<String> l = new ArrayList<>(List.of("5", "10", "25", "50", "100", "#global"));
                for (World w : Bukkit.getWorlds()) l.add("#" + w.getName());
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

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
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
        if (sub.equals("migrate-db") && sender.hasPermission(PERM_ADMIN)) {
            List<String> out = new ArrayList<>();
            if (args.length == 2) { for (String n : DbType.NAMES) if (n.startsWith(lastLower)) out.add(n); }
            else if ("--full-validation".startsWith(lastLower)) out.add("--full-validation");
            return out;
        }

        List<String> keys, tags;
        switch (sub) {
            case "lookup", "near" -> { keys = KEYS_LOOKUP; tags = TAGS_LOOKUP; }
            case "rollback", "restore" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = KEYS_ROLLBACK; tags = TAGS_ROLLBACK;
            }
            case "purge" -> {
                if (!sender.hasPermission(PERM_ADMIN)) return List.of();
                keys = KEYS_PURGE; tags = TAGS_PURGE;
            }
            default -> { return List.of(); }
        }

        // Bereits benutzte Parameter/Tags nicht erneut vorschlagen (Kurz- und Langform gelten als gleich)
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
