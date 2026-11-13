package de.muffin.universalprotect.api;

import de.muffin.universalprotect.*;

import java.util.*;
import java.util.function.Function;

public final class UniversalProtectAPI {
    public static final int API_VERSION = 13;

    private final UniversalProtect plugin;

    public UniversalProtectAPI(UniversalProtect plugin) { this.plugin = plugin; }

    private DatabaseManager db() { return plugin.db(); }

    public int APIVersion() { return API_VERSION; }

    public boolean isEnabled() { return plugin.isEnabled() && plugin.db() != null; }

    public void testAPI() { plugin.getLogger().info("API test successful (API version " + API_VERSION + ")."); }

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

    private static LookupOptions at(Loc l, int time, String user) {
        LookupOptions.Builder b = LookupOptions.builder().time(time);
        if (l != null) b.location(l);
        if (user != null) b.user(user);
        return b.build();
    }

    private static String[] legacy(LogRow r) {
        String blockData = r.data() != null && Actions.BLOCK.contains(r.action()) ? r.data() : "";
        return new String[]{String.valueOf(r.time() / 1000), String.valueOf(r.player()), String.valueOf(r.x()), String.valueOf(r.y()),
                String.valueOf(r.z()), "0", "0", String.valueOf(Actions.cpId(r.action())),
                r.rolledBack() ? "1" : "0", String.valueOf(r.world()), blockData};
    }

    private static List<String[]> legacy(List<LogRow> rows) {
        List<String[]> out = new ArrayList<>(rows.size());
        for (LogRow r : rows) out.add(legacy(r));
        return out;
    }

    public List<String[]> blockLookup(Loc block, int time) {
        return legacy(fetch(at(block, time, null).toQuery(Actions.expand("inspect")), null));
    }

    public List<String[]> sessionLookup(String user, int time) {
        return legacy(fetch(at(null, time, user).toQuery(Set.of(Actions.JOIN, Actions.QUIT)), null));
    }

    public List<String[]> queueLookup(Loc block) {
        List<String[]> out = new ArrayList<>();
        for (LogEntry e : db().pending()) {
            if (e.x() == block.x() && e.y() == block.y() && e.z() == block.z() && block.world().equals(e.world())) {
                out.add(legacy(LogRow.fromEntry(0L, e)));
            }
        }
        return out;
    }

    public ParseResult parseResult(String[] row) { return new ParseResult(row); }

    private Query legacyQuery(int time, List<String> restrictUsers, List<String> excludeUsers, List<String> restrictBlocks,
                              List<String> excludeBlocks, List<Integer> actionList, int radius, Loc radiusLocation) {
        LookupOptions.Builder b = LookupOptions.builder().time(time).users(restrictUsers).excludeUsers(excludeUsers);
        if (radiusLocation != null && radius > 0) b.radius(radiusLocation, radius);
        Query q = b.build().toQuery(Set.of());
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (restrictBlocks != null) for (String o : restrictBlocks) inc.add(name(o));
        if (excludeBlocks != null) for (String o : excludeBlocks) exc.add(name(o));
        q.include = inc;
        q.exclude = exc;
        Set<String> acts = new LinkedHashSet<>();
        if (actionList != null) for (int id : actionList) acts.addAll(Actions.fromCpId(id));
        q.actions = acts.isEmpty() ? null : acts;
        return q;
    }

    private static String name(String o) { return String.valueOf(o).toLowerCase(Locale.ROOT).replace("minecraft:", ""); }

    public List<String[]> performLookup(int time, List<String> restrictUsers, List<String> excludeUsers, List<String> restrictBlocks,
                                        List<String> excludeBlocks, List<Integer> actionList, int radius, Loc radiusLocation) {
        return legacy(fetch(legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation), null));
    }

    public List<String[]> performPartialLookup(int time, List<String> restrictUsers, List<String> excludeUsers, List<String> restrictBlocks,
                                               List<String> excludeBlocks, List<Integer> actionList, int radius, Loc radiusLocation,
                                               int limitOffset, int limitCount) {
        LookupOptions lim = LookupOptions.builder().limit(limitOffset, limitCount).build();
        return legacy(fetch(legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation), lim));
    }

    public List<String[]> performRollback(int time, List<String> restrictUsers, List<String> excludeUsers, List<String> restrictBlocks,
                                          List<String> excludeBlocks, List<Integer> actionList, int radius, Loc radiusLocation) {
        Query q = legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation);
        List<String[]> result = legacy(fetch(q, null));
        plugin.rollback().startSilent(q, false);
        return result;
    }

    public List<String[]> performRestore(int time, List<String> restrictUsers, List<String> excludeUsers, List<String> restrictBlocks,
                                         List<String> excludeBlocks, List<Integer> actionList, int radius, Loc radiusLocation) {
        Query q = legacyQuery(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusLocation);
        List<String[]> result = legacy(fetch(q, null));
        plugin.rollback().startSilent(q, true);
        return result;
    }

    public void performPurge(int time) {
        if (time < 86_400) return;
        final Query q = new Query();
        q.until = System.currentTimeMillis() - time * 1000L;
        db().submit(() -> {
            try { db().purgeNow(q); } catch (Exception e) { plugin.getLogger().warning("API purge failed: " + e.getMessage()); }
        });
    }

    private static final Set<String> BLOCK_ACTIONS = Set.of(Actions.PLACED, Actions.BROKE, Actions.USE);

    public List<LogResult.BlockResult> blockLookup(LookupOptions options) {
        return typed(options, BLOCK_ACTIONS, LogResult.BlockResult::new);
    }

    public List<LogResult.BlockResult> blockLookup(Loc block, LookupOptions options) {
        return typed(LookupOptions.builder().time(options.getTime()).users(options.getUsers()).location(block)
                .limit(options.limitOffset(), options.limitCount()).build(), BLOCK_ACTIONS, LogResult.BlockResult::new);
    }

    public List<LogResult.EntityResult> entityLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.KILL, Actions.SPAWN), LogResult.EntityResult::new);
    }

    public List<LogResult.ContainerResult> containerLookup(Loc location, int time) {
        return typed(at(location, time, null), Actions.CONTAINER, LogResult.ContainerResult::new);
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
        return typed(at(null, time, user), Set.of(Actions.CHAT), LogResult.MessageResult::new);
    }

    public List<LogResult.MessageResult> chatLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.CHAT), LogResult.MessageResult::new);
    }

    public List<LogResult.MessageResult> commandLookup(String user, int time) {
        return typed(at(null, time, user), Set.of(Actions.COMMAND), LogResult.MessageResult::new);
    }

    public List<LogResult.MessageResult> commandLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.COMMAND), LogResult.MessageResult::new);
    }

    public List<LogResult.SignResult> signLookup(Loc location, int time) {
        return typed(at(location, time, null), Set.of(Actions.SIGN), LogResult.SignResult::new);
    }

    public List<LogResult.SignResult> signLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.SIGN), LogResult.SignResult::new);
    }

    public List<LogResult.SessionResult> sessionLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.JOIN, Actions.QUIT), LogResult.SessionResult::new);
    }

    public List<LogResult.UsernameResult> usernameLookup(String user, int time) {
        return typed(at(null, time, user), Set.of(Actions.USERNAME), LogResult.UsernameResult::new);
    }

    public List<LogResult.UsernameResult> usernameLookup(LookupOptions options) {
        return typed(options, Set.of(Actions.USERNAME), LogResult.UsernameResult::new);
    }

    private boolean enqueue(String type, String user, String action, String data, Loc l, String oldData) {
        if (user == null || l == null || l.world() == null || !isEnabled()) return false;
        var p = Mc.player(user);
        String id = p == null ? null : p.getUUID().toString();
        db().log(LogEntry.of(type, user, id, action, data, l.world(), l.x(), l.y(), l.z()).withOld(oldData, null));
        return true;
    }

    public boolean logPlacement(String user, Loc location, String blockState) {
        return enqueue("block", user, Actions.PLACED, blockState, location, null);
    }

    public boolean logRemoval(String user, Loc location, String blockState) {
        return enqueue("block", user, Actions.BROKE, blockState, location, blockState);
    }

    public boolean logChat(String user, Loc location, String message) {
        return enqueue("chat", user, Actions.CHAT, message, location, null);
    }

    public boolean logCommand(String user, Loc location, String command) {
        return enqueue("command", user, Actions.COMMAND, command, location, null);
    }

    public boolean logContainerTransaction(String user, Loc location) {
        return plugin.hooks() != null && plugin.hooks().flushContainer(user, location);
    }

    public boolean logInteraction(String user, Loc location, String blockId) {
        return enqueue("interact", user, Actions.USE, Msg.cleanBlock(blockId), location, null);
    }

    public boolean hasPlaced(String user, Loc block, int time, int offset) { return has(Actions.PLACED, user, block, time, offset); }

    public boolean hasRemoved(String user, Loc block, int time, int offset) { return has(Actions.BROKE, user, block, time, offset); }

    private boolean has(String action, String user, Loc block, int time, int offset) {
        Query q = new Query();
        q.users = List.of(user);
        q.actions = Set.of(action);
        long now = System.currentTimeMillis();
        q.since = now - time * 1000L;
        if (offset > 0) q.until = now - offset * 1000L;
        q.worlds = List.of(block.world());
        q.x = block.x(); q.y = block.y(); q.z = block.z(); q.radius = 0;
        try {
            db().flushNow();
            return db().call(() -> db().count(q) > 0);
        } catch (Exception e) {
            return false;
        }
    }

    public static final class ParseResult {
        private final String[] row;

        ParseResult(String[] row) { this.row = row; }

        private int i(int idx) { try { return Integer.parseInt(row[idx]); } catch (Exception e) { return 0; } }

        public String getPlayer() { return row[1]; }
        public long getTimestamp() { return i(0) * 1000L; }
        public int getX() { return i(2); }
        public int getY() { return i(3); }
        public int getZ() { return i(4); }
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
        public String getBlockData() { return row.length > 10 ? row[10] : ""; }
        public String getType() {
            String d = getBlockData();
            if (d.isEmpty()) return null;
            String id = d.split("\\[")[0];
            return id.contains(":") ? id : "minecraft:" + id;
        }
    }
}
