package de.muffin.universalprotect.api;

import de.muffin.universalprotect.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Function;

/**
 * Java-API von UniversalProtect, kompatibel zu den Methoden der CoreProtect API v13
 * (Abruf: {@code Bukkit.getServicesManager().load(UniversalProtectAPI.class)}).
 * Alle Lookups sind blockierend und sollten nicht im Hauptthread laufen.
 *
 * <p>Format der {@code String[]}-Ergebnisse (wie CoreProtect): [0] Zeit (Sekunden), [1] Benutzer, [2] x, [3] y, [4] z,
 * [5] Typ-ID (Material-Ordinal), [6] Daten (0), [7] Aktions-ID, [8] zurückgerollt (0/1), [9] Weltname, [10] BlockData.</p>
 */
public final class UniversalProtectAPI {
    public static final int API_VERSION = 13;

    private final UniversalProtect plugin;

    public UniversalProtectAPI(UniversalProtect plugin) { this.plugin = plugin; }

    private DatabaseManager db() { return plugin.db(); }

    // ------------------------------------------------------------------ Status

    public int APIVersion() { return API_VERSION; }

    public boolean isEnabled() { return plugin.isEnabled() && plugin.db() != null; }

    public void testAPI() { plugin.getLogger().info("API test successful (API version " + API_VERSION + ")."); }

    // ------------------------------------------------------------------ Intern

    private List<LogRow> fetch(Query q, LookupOptions o) {
        long limit = 0, offset = 0;
        if (o != null && o.limitCount() > 0) { limit = o.limitCount(); offset = o.limitOffset(); }
        final long fl = limit, fo = offset;
        try {
            return db().call(() -> db().selectRows(q, false, fl, fo, null, null));
        } catch (Exception e) {
            plugin.getLogger().warning("API lookup failed: " + e.getMessage());
            return List.of();
        }
    }

    private <T extends LogResult> List<T> typed(LookupOptions o, Set<String> defaults, Function<LogRow, T> f) {
        Query q = o.toQuery(defaults);
        List<T> out = new ArrayList<>();
        for (LogRow r : fetch(q, o)) out.add(f.apply(r));
        return out;
    }

    private static LookupOptions at(Location l, int time, String user) {
        LookupOptions.Builder b = LookupOptions.builder().time(time);
        if (l != null) b.location(l);
        if (user != null) b.user(user);
        return b.build();
    }

    private static String[] legacy(LogRow r) {
        Material m = r.data() == null ? null : Material.matchMaterial(r.data().split("[\\[ ]")[0]);
        String blockData = r.data() != null && Actions.BLOCK.contains(r.action()) ? r.data() : "";
        return new String[]{String.valueOf(r.time() / 1000), String.valueOf(r.player()), String.valueOf(r.x()), String.valueOf(r.y()),
                String.valueOf(r.z()), String.valueOf(m == null ? 0 : m.ordinal()), "0", String.valueOf(Actions.cpId(r.action())),
                r.rolledBack() ? "1" : "0", String.valueOf(r.world()), blockData};
    }

    private static List<String[]> legacy(List<LogRow> rows) {
        List<String[]> out = new ArrayList<>(rows.size());
        for (LogRow r : rows) out.add(legacy(r));
        return out;
    }

    // ------------------------------------------------------------------ Legacy-Lookups

    public List<String[]> blockLookup(Block block, int time) {
        return legacy(fetch(at(block.getLocation(), time, null).toQuery(Actions.expand("inspect")), null));
    }

    public List<String[]> sessionLookup(String user, int time) {
        return legacy(fetch(at(null, time, user).toQuery(Set.of(Actions.JOIN, Actions.QUIT)), null));
    }

    /** Noch nicht in die Datenbank geschriebene Einträge an diesem Block. */
    public List<String[]> queueLookup(Block block) {
        List<String[]> out = new ArrayList<>();
        for (LogEntry e : db().pending()) {
            if (e.x() == block.getX() && e.y() == block.getY() && e.z() == block.getZ() && block.getWorld().getName().equals(e.world())) {
                out.add(legacy(LogRow.fromEntry(0L, e)));
            }
        }
        return out;
    }

    public ParseResult parseResult(String[] row) { return new ParseResult(row); }

    private Query legacyQuery(int time, List<String> restrictUsers, List<String> excludeUsers, List<Object> restrictBlocks,
                              List<Object> excludeBlocks, List<Integer> actionList, int radius, Location radiusLocation) {
        LookupOptions.Builder b = LookupOptions.builder().time(time).users(restrictUsers).excludeUsers(excludeUsers);
        if (radiusLocation != null && radius > 0) b.radius(radiusLocation, radius);
        Query q = b.build().toQuery(Set.of());
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (restrictBlocks != null) for (Object o : restrictBlocks) inc.add(nameOf(o));
        if (excludeBlocks != null) for (Object o : excludeBlocks) exc.add(nameOf(o));
        q.include = inc;
        q.exclude = exc;
        Set<String> acts = new LinkedHashSet<>();
        if (actionList != null) for (int id : actionList) acts.addAll(Actions.fromCpId(id));
        q.actions = acts.isEmpty() ? null : acts;
        return q;
    }

    private static String nameOf(Object o) {
        if (o instanceof Material m) return m.name().toLowerCase(Locale.ROOT);
        if (o instanceof EntityType e) return e.name().toLowerCase(Locale.ROOT);
        return String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    public List<String[]> performLookup(int time, List<String> restrictUsers, List<String> excludeUsers, List<Object> restrictBlocks,
                                        List<Object> excludeBlocks, List<Integer> actionList, int radius, Location radiusLocation) {
        return legacy(fetch(legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation), null));
    }

    public List<String[]> performPartialLookup(int time, List<String> restrictUsers, List<String> excludeUsers, List<Object> restrictBlocks,
                                               List<Object> excludeBlocks, List<Integer> actionList, int radius, Location radiusLocation,
                                               int limitOffset, int limitCount) {
        LookupOptions lim = LookupOptions.builder().limit(limitOffset, limitCount).build();
        return legacy(fetch(legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation), lim));
    }

    public List<String[]> performRollback(int time, List<String> restrictUsers, List<String> excludeUsers, List<Object> restrictBlocks,
                                          List<Object> excludeBlocks, List<Integer> actionList, int radius, Location radiusLocation) {
        Query q = legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation);
        List<String[]> result = legacy(fetch(q, null));
        plugin.rollback().startSilent(q, false);
        return result;
    }

    public List<String[]> performRestore(int time, List<String> restrictUsers, List<String> excludeUsers, List<Object> restrictBlocks,
                                         List<Object> excludeBlocks, List<Integer> actionList, int radius, Location radiusLocation) {
        Query q = legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation);
        List<String[]> result = legacy(fetch(q, null));
        plugin.rollback().startSilent(q, true);
        return result;
    }

    /** Löscht Daten älter als {@code time} Sekunden (mindestens 24 Stunden). */
    public void performPurge(int time) {
        if (time < 86_400) return;
        final Query q = new Query();
        q.until = System.currentTimeMillis() - time * 1000L;
        db().submit(() -> {
            try { db().purgeNow(q); } catch (Exception e) { plugin.getLogger().warning("API purge failed: " + e.getMessage()); }
        });
    }

    // ------------------------------------------------------------------ Typisierte Lookups

    private static final Set<String> BLOCK_ACTIONS = Set.of(Actions.PLACED, Actions.BROKE, Actions.USE);

    public List<LogResult.BlockResult> blockLookup(LookupOptions options) {
        return typed(options, BLOCK_ACTIONS, LogResult.BlockResult::new);
    }

    public List<LogResult.BlockResult> blockLookup(Block block, LookupOptions options) {
        Query q = options.toQuery(BLOCK_ACTIONS);
        q.worlds = List.of(block.getWorld().getName());
        q.x = block.getX(); q.y = block.getY(); q.z = block.getZ(); q.radius = 0;
        List<LogResult.BlockResult> out = new ArrayList<>();
        for (LogRow r : fetch(q, options)) out.add(new LogResult.BlockResult(r));
        return out;
    }

    public List<LogResult.EntityResult> entityLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.KILL, Actions.SPAWN), LogResult.EntityResult::new);
    }

    public List<LogResult.ContainerResult> containerLookup(Location location, int time) {
        return containerLookup(at(location, time, null));
    }

    public List<LogResult.ContainerResult> containerLookup(LookupOptions options) {
        return typed(options, Actions.CONTAINER, LogResult.ContainerResult::new);
    }

    public List<LogResult.ItemResult> itemLookup(LookupOptions options) {
        return typed(options, Actions.ITEM_ALL, LogResult.ItemResult::new);
    }

    public List<LogResult.InventoryResult> inventoryLookup(LookupOptions options) {
        return typed(options, Actions.INVENTORY, LogResult.InventoryResult::new);
    }

    public List<LogResult.MessageResult> chatLookup(String user, int time) {
        return chatLookup(at(null, time, user));
    }

    public List<LogResult.MessageResult> chatLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.CHAT), LogResult.MessageResult::new);
    }

    public List<LogResult.MessageResult> commandLookup(String user, int time) {
        return commandLookup(at(null, time, user));
    }

    public List<LogResult.MessageResult> commandLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.COMMAND), LogResult.MessageResult::new);
    }

    public List<LogResult.SignResult> signLookup(Location location, int time) {
        return signLookup(at(location, time, null));
    }

    public List<LogResult.SignResult> signLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.SIGN), LogResult.SignResult::new);
    }

    public List<LogResult.SessionResult> sessionLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.JOIN, Actions.QUIT), LogResult.SessionResult::new);
    }

    public List<LogResult.UsernameResult> usernameLookup(String user, int time) {
        return usernameLookup(at(null, time, user));
    }

    public List<LogResult.UsernameResult> usernameLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.USERNAME), LogResult.UsernameResult::new);
    }

    // ------------------------------------------------------------------ Logging

    private boolean enqueue(String type, String user, String action, String data, Location l, String oldData) {
        if (user == null || l == null || l.getWorld() == null || !isEnabled()) return false;
        UUID id = null;
        Player p = Bukkit.getPlayerExact(user);
        if (p != null) id = p.getUniqueId();
        LogEntry e = LogEntry.of(type, user, id == null ? null : id.toString(), action, data, l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ()).withOld(oldData, null);
        db().log(e);
        return true;
    }

    public boolean logPlacement(String user, BlockState state) {
        return logPlacement(user, state.getLocation(), state.getType(), state.getBlockData());
    }

    public boolean logPlacement(String user, Location location, Material type, BlockData blockData) {
        return enqueue("block", user, Actions.PLACED, blockData == null ? type.createBlockData().getAsString() : blockData.getAsString(), location,
                location.getWorld() == null ? null : location.getBlock().getBlockData().getAsString());
    }

    public boolean logPlacement(String user, Location location, BlockData blockData) {
        return logPlacement(user, location, blockData.getMaterial(), blockData);
    }

    public boolean logRemoval(String user, BlockState state) {
        return logRemoval(user, state.getLocation(), state.getType(), state.getBlockData());
    }

    public boolean logRemoval(String user, Location location, Material type, BlockData blockData) {
        String s = blockData == null ? type.createBlockData().getAsString() : blockData.getAsString();
        return enqueue("block", user, Actions.BROKE, s, location, s);
    }

    public boolean logRemoval(String user, Location location, BlockData blockData) {
        return logRemoval(user, location, blockData.getMaterial(), blockData);
    }

    public boolean logChat(Player player, String message) {
        return enqueue("chat", player.getName(), Actions.CHAT, message, player.getLocation(), null);
    }

    public boolean logCommand(Player player, String command) {
        return enqueue("command", player.getName(), Actions.COMMAND, command, player.getLocation(), null);
    }

    /** Vergleicht den aktuellen Inhalt mit dem Stand beim Öffnen und loggt die Differenz. */
    public boolean logContainerTransaction(String user, Location location) {
        return plugin.actionLogger() != null && plugin.actionLogger().flushContainer(user, location);
    }

    public boolean logInteraction(String user, Location location) {
        if (location == null || location.getWorld() == null) return false;
        return enqueue("interact", user, Actions.USE, location.getBlock().getType().name().toLowerCase(Locale.ROOT), location, null);
    }

    public boolean hasPlaced(String user, Block block, int time, int offset) { return has(Actions.PLACED, user, block, time, offset); }

    public boolean hasRemoved(String user, Block block, int time, int offset) { return has(Actions.BROKE, user, block, time, offset); }

    private boolean has(String action, String user, Block block, int time, int offset) {
        Query q = new Query();
        q.users = List.of(user);
        q.actions = Set.of(action);
        long now = System.currentTimeMillis();
        q.since = now - time * 1000L;
        if (offset > 0) q.until = now - offset * 1000L;
        q.worlds = List.of(block.getWorld().getName());
        q.x = block.getX(); q.y = block.getY(); q.z = block.getZ(); q.radius = 0;
        try {
            db().flushNow();
            return db().call(() -> db().count(q) > 0);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ParseResult

    /** Entspricht CoreProtectAPI.ParseResult. */
    public static final class ParseResult {
        private final String[] row;

        ParseResult(String[] row) { this.row = row; }

        private int i(int idx) { try { return Integer.parseInt(row[idx]); } catch (Exception e) { return 0; } }

        public String getPlayer() { return row[1]; }
        public long getTimestamp() { return i(0) * 1000L; }
        /** @deprecated Sekunden; verwende {@link #getTimestamp()} */
        @Deprecated public int getTime() { return i(0); }
        public int getX() { return i(2); }
        public int getY() { return i(3); }
        public int getZ() { return i(4); }
        public Material getType() {
            Material[] all = Material.values();
            int o = i(5);
            return o >= 0 && o < all.length ? all[o] : Material.AIR;
        }
        /** @deprecated nicht mehr verwendet */
        @Deprecated public int getData() { return i(6); }
        public int getActionId() { return i(7); }
        public String getActionString() {
            return switch (getActionId()) {
                case 0 -> "break";
                case 1 -> "place";
                case 2 -> "click";
                case 3 -> "kill";
                case 13 -> "spawn";
                default -> "action-" + getActionId();
            };
        }
        public boolean isRolledBack() { return "1".equals(row[8]); }
        public String worldName() { return row[9]; }
        public World getWorld() { return Bukkit.getWorld(row[9]); }
        public BlockData getBlockData() {
            try { return row.length > 10 && !row[10].isEmpty() ? Bukkit.createBlockData(row[10]) : null; } catch (Exception e) { return null; }
        }
        public EntityType getEntityType() {
            EntityType[] all = EntityType.values();
            int o = i(5);
            return o >= 0 && o < all.length ? all[o] : EntityType.UNKNOWN;
        }
    }
}
