package de.muffin.universalprotect;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DatabaseManager {
    public static final String PREFIX = "<gradient:#00fb9a:#00d2ff><bold>UP</bold></gradient> <dark_gray>»</dark_gray> ";
    private static final int ROLLBACK_BATCH_PER_TICK = 500;
    
    private static final String INSERT_SQL = "INSERT INTO logs (time_millis, type, player_name, action, data, world, x, y, z, old_data, old_extra) VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    private record LogEntry(long time, String type, String player, String action, String data, String world, int x, int y, int z, String oldData, String oldExtra) {}
    private record BlockUpdate(int x, int y, int z, String data, String extra) {}
    private record Pos(int x, int y, int z) {}

    public record Query(String type, String user, String action, long since, int radius, List<String> worlds, String originWorld, int x, int y, int z, int page, String include, String exclude, String filter, String tags) {
        public static Query from(Player p, Map<String, String> params, String defaultRadiusStr, String type) {
            long since = 0;
            if (params.containsKey("t")) since = System.currentTimeMillis() - ParamUtils.parseTime(params.get("t"));
            
            int radius = -1;
            List<String> worlds = new ArrayList<>();
            String radStr = params.getOrDefault("r", defaultRadiusStr);
            if (radStr != null) {
                if (radStr.startsWith("#")) {
                    if (!radStr.equalsIgnoreCase("#global")) {
                        for (String s : radStr.split(",")) worlds.add(s.replace("#", ""));
                    }
                } else {
                    try { radius = Integer.parseInt(radStr); worlds.add(p.getWorld().getName()); } catch (Exception ignored) {}
                }
            }

            int page = 1;
            Location l = p.getLocation();
            return new Query(type, params.get("u"), params.get("a"), since, radius, worlds, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), page, params.get("i"), params.get("e"), params.get("f"), params.get("tags"));
        }
    }

    private final UniversalProtect plugin;
    private final Queue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Connection connection;
    
    // Store last action for /up undo
    private final Map<UUID, Map<String, Map<Pos, BlockUpdate>>> undoCache = new ConcurrentHashMap<>();

    public DatabaseManager(UniversalProtect plugin) throws Exception {
        this.plugin = plugin;
        connect();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (plugin.isConsumerActive() && !executor.isShutdown()) {
                try { executor.submit(this::flush); } catch (Exception ignored) {}
            }
        }, 40L, 40L);
    }

    private void connect() throws Exception {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "database.db").getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, time_millis INTEGER, type VARCHAR(16), player_name VARCHAR(32), action VARCHAR(16), data TEXT, world VARCHAR(50), x INTEGER, y INTEGER, z INTEGER, old_data TEXT, old_extra TEXT)");
        }
    }

    public void logAction(String type, String playerName, String action, String data, String world, int x, int y, int z, String oldData, String oldExtra) {
        if (plugin.isConsumerActive()) queue.add(new LogEntry(System.currentTimeMillis(), type, playerName, action, data, world, x, y, z, oldData, oldExtra));
    }

    private void flush() {
        if (connection == null || queue.isEmpty()) return;
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e; while ((e = queue.poll()) != null) batch.add(e);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                for (LogEntry le : batch) {
                    ps.setLong(1, le.time()); ps.setString(2, le.type()); ps.setString(3, le.player());
                    ps.setString(4, le.action()); ps.setString(5, le.data()); ps.setString(6, le.world());
                    ps.setInt(7, le.x()); ps.setInt(8, le.y()); ps.setInt(9, le.z());
                    ps.setString(10, le.oldData()); ps.setString(11, le.oldExtra()); ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException ex) { try { connection.rollback(); } catch (Exception ignored) {} }
          finally { try { connection.setAutoCommit(true); } catch (Exception ignored) {} }
    }

    private String buildWhere(Query q, List<Object> args) {
        StringBuilder sb = new StringBuilder("1=1");
        if (q.user() != null) { sb.append(" AND player_name = ? COLLATE NOCASE"); args.add(q.user()); }
        if (q.action() != null) {
            if (q.action().startsWith("+")) { sb.append(" AND action IN ('placed', 'join', 'put')"); }
            else if (q.action().startsWith("-")) { sb.append(" AND action IN ('broke', 'quit', 'take')"); }
            else { sb.append(" AND action = ?"); args.add(q.action()); }
        }
        if (q.since() > 0) { sb.append(" AND time_millis >= ?"); args.add(q.since()); }
        if (q.worlds() != null && !q.worlds().isEmpty()) {
            sb.append(" AND world IN (");
            for (int i = 0; i < q.worlds().size(); i++) { sb.append("?"); sb.append(i < q.worlds().size() - 1 ? "," : ")"); args.add(q.worlds().get(i)); }
        }
        if (q.radius() >= 0) {
            sb.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            args.add(q.x() - q.radius()); args.add(q.x() + q.radius());
            args.add(q.y() - q.radius()); args.add(q.y() + q.radius());
            args.add(q.z() - q.radius()); args.add(q.z() + q.radius());
        }
        if (q.include() != null) { sb.append(" AND data LIKE ?"); args.add("%" + q.include().split(",")[0] + "%"); }
        if (q.exclude() != null) { sb.append(" AND data NOT LIKE ?"); args.add("%" + q.exclude().split(",")[0] + "%"); }
        return sb.toString();
    }

    public void lookup(Player player, Query q) {
        executor.submit(() -> {
            flush(); List<Object> args = new ArrayList<>();
            String sql = "SELECT time_millis, type, player_name, action, data, world, x, y, z FROM logs WHERE " + buildWhere(q, args) + " ORDER BY time_millis DESC LIMIT 15";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("\n<dark_gray>-----</dark_gray> " + PREFIX + "<gray>Lookup</gray> <dark_gray>-----</dark_gray>"));
                    int count = 0; long now = System.currentTimeMillis();
                    boolean countMode = q.tags() != null && q.tags().contains("#count");
                    
                    while (rs.next()) {
                        count++;
                        if (countMode) continue;
                        String data = rs.getString("data").replace("minecraft:", "");
                        String act = rs.getString("action").equals("broke") ? "<red>broke</red>" : "<green>placed</green>";
                        String time = "<gray>" + ((now - rs.getLong("time_millis"))/60000) + "m ago <dark_gray>-</dark_gray>";
                        String loc = " <dark_gray>[<hover:show_text:'Teleport'><click:run_command:'/up tp " + rs.getString("world") + " " + rs.getInt("x") + " " + rs.getInt("y") + " " + rs.getInt("z") + "'>" + rs.getInt("x") + " " + rs.getInt("y") + " " + rs.getInt("z") + "</click></hover>]</dark_gray>";
                        player.sendMessage(MiniMessage.miniMessage().deserialize(time + " <aqua>" + rs.getString("player_name") + "</aqua> " + act + " <white>" + data + "</white>" + loc));
                    }
                    if (countMode) player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<white>" + count + "</white> <gray>records found.</gray>"));
                    else if (count == 0) player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<red>No records found.</red>"));
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void processAction(Player player, Query q, boolean isRestore) {
        executor.submit(() -> {
            flush(); List<Object> args = new ArrayList<>();
            String sql = "SELECT action, data, old_data, old_extra, world, x, y, z FROM logs WHERE " + buildWhere(q, args) + " ORDER BY time_millis DESC";
            Map<String, Map<Pos, BlockUpdate>> updates = new HashMap<>();
            boolean preview = q.tags() != null && q.tags().contains("#preview");

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String w = rs.getString("world");
                        int x = rs.getInt("x"), y = rs.getInt("y"), z = rs.getInt("z");
                        String oldData = rs.getString("old_data");
                        String currentData = rs.getString("data");
                        
                        String target;
                        if (isRestore) {
                            target = "broke".equals(rs.getString("action")) ? currentData : (oldData != null ? oldData : "minecraft:air");
                        } else {
                            target = oldData != null ? oldData : ("broke".equals(rs.getString("action")) ? currentData : "minecraft:air");
                        }
                        updates.computeIfAbsent(w, k -> new HashMap<>()).put(new Pos(x, y, z), new BlockUpdate(x, y, z, target, rs.getString("old_extra")));
                    }
                }
            } catch (Exception e) { e.printStackTrace(); return; }

            int total = updates.values().stream().mapToInt(Map::size).sum();
            if (preview) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<yellow>Preview complete. <white>" + total + "</white> blocks would be modified.</yellow>"));
                return;
            }
            
            undoCache.put(player.getUniqueId(), updates); // Save for undo
            Bukkit.getScheduler().runTask(plugin, () -> applyBlocks(player, updates, isRestore ? "Restore" : "Rollback"));
        });
    }
    
    public void undo(Player player) {
        Map<String, Map<Pos, BlockUpdate>> last = undoCache.remove(player.getUniqueId());
        if (last == null || last.isEmpty()) { player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<red>No pending actions to undo.</red>")); return; }
        
        // Invert updates
        Map<String, Map<Pos, BlockUpdate>> inverse = new HashMap<>();
        for (String w : last.keySet()) {
            for (Map.Entry<Pos, BlockUpdate> e : last.get(w).entrySet()) {
                Block b = Bukkit.getWorld(w).getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z());
                inverse.computeIfAbsent(w, k -> new HashMap<>()).put(e.getKey(), new BlockUpdate(b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString(), BlockExtras.capture(b.getState())));
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> applyBlocks(player, inverse, "Undo"));
    }

    public void purge(Player player, long beforeMillis) {
        executor.submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM logs WHERE time_millis < ?")) {
                ps.setLong(1, beforeMillis);
                int del = ps.executeUpdate();
                player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<green>Purge complete. Removed <white>" + del + "</white> rows.</green>"));
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void applyBlocks(Player player, Map<String, Map<Pos, BlockUpdate>> updatesMap, String type) {
        List<Runnable> tasks = new ArrayList<>();
        for (Map.Entry<String, Map<Pos, BlockUpdate>> entry : updatesMap.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey()); if (world == null) continue;
            for (BlockUpdate u : entry.getValue().values()) {
                tasks.add(() -> {
                    try {
                        Block block = world.getBlockAt(u.x(), u.y(), u.z());
                        block.setBlockData(Bukkit.createBlockData(u.data()), false);
                        if (u.extra() != null) BlockExtras.apply(block, u.extra());
                    } catch (Exception ignored) {}
                });
            }
        }
        if (tasks.isEmpty()) { player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<red>No modifications needed.</red>")); return; }
        
        new BukkitRunnable() {
            int index = 0;
            @Override public void run() {
                int end = Math.min(index + ROLLBACK_BATCH_PER_TICK, tasks.size());
                for (; index < end; index++) tasks.get(index).run();
                if (index >= tasks.size()) { cancel(); player.sendMessage(MiniMessage.miniMessage().deserialize(PREFIX + "<green>" + type + " complete! <white>" + tasks.size() + "</white> blocks modified.</green>")); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
    public void close() { try { if (!executor.isShutdown()) { executor.submit(this::flush); executor.shutdown(); } connection.close(); } catch (Exception ignored) {} }
}
