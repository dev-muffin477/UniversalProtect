package de.muffin.universalprotect;

import de.muffin.universalprotect.api.PreLogEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Datenbank-Schicht: Verbindung (SQLite / MySQL / DuckDB / ClickHouse), asynchrones Schreiben, Abfragen,
 * Benutzer-/UUID-Auflösung, Purge und Statistik. Alle Datenbankzugriffe laufen auf einem einzigen Thread.
 */
public final class DatabaseManager {
    public static final UUID CONSOLE_KEY = new UUID(0L, 0L);
    private static final int FLAG_CHUNK = 1000;
    private static final String SELECT_ROWS = "SELECT " + DbType.COLS + " FROM logs WHERE ";

    @FunctionalInterface
    public interface SqlTask { void run() throws SQLException; }

    private final UniversalProtect plugin;
    private final ExecutorService executor;
    private volatile Thread dbThread;
    private final Queue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private final List<LogRow> retry = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile Connection connection;
    private volatile DbType type;
    private volatile boolean migrating;
    private final List<Object[]> flagReplay = Collections.synchronizedList(new ArrayList<>());

    // Benutzer-Verzeichnis: kleingeschriebener Name -> UUIDs / kanonische Schreibweise
    private final Map<String, Set<String>> nameToUuids = new ConcurrentHashMap<>();
    private final Map<String, String> canonical = new ConcurrentHashMap<>();
    private final Map<String, String> uuidToName = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> uuidToNames = new ConcurrentHashMap<>();
    private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Query> lastQueries = new ConcurrentHashMap<>();

    public DatabaseManager(UniversalProtect plugin, DbType type) throws Exception {
        this.plugin = plugin;
        this.type = type;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UniversalProtect-DB");
            dbThread = t;
            return t;
        });
        this.connection = type.connect(plugin);
        this.nextId.set(readMaxId(connection) + 1);
        loadUsers();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> submit(this::flush), 40L, 40L);
    }

    // ------------------------------------------------------------------ Grundfunktionen

    public DbType type() { return type; }
    Connection connection() { return connection; }
    ExecutorService executor() { return executor; }
    AtomicLong nextId() { return nextId; }
    public boolean isMigrating() { return migrating; }
    void setMigrating(boolean m) { migrating = m; if (m) flagReplay.clear(); }
    List<Object[]> flagReplay() { return flagReplay; }

    public void submit(Runnable r) {
        if (executor.isShutdown()) return;
        try { executor.submit(r); } catch (RejectedExecutionException ignored) { /* shutting down */ }
    }

    /** Führt eine Aufgabe auf dem Datenbank-Thread aus und wartet auf das Ergebnis (für die API). */
    public <T> T call(Callable<T> task) throws Exception {
        if (Thread.currentThread() == dbThread) return task.call();
        return executor.submit(task).get(60, TimeUnit.SECONDS);
    }

    private static long readMaxId(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(id) FROM logs")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public void withTx(Connection c, DbType t, SqlTask task) throws SQLException {
        if (t == DbType.CLICKHOUSE) { task.run(); return; } // ClickHouse kennt keine Transaktionen
        c.setAutoCommit(false);
        try {
            task.run();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { /* nothing to do */ }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { /* nothing to do */ }
        }
    }

    private static void setStr(PreparedStatement ps, int i, String s) throws SQLException {
        if (s == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, s);
    }

    /** Schreibt Zeilen mit festen IDs (Logging und Migration). */
    public static void insertRows(Connection c, List<LogRow> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(DbType.INSERT_SQL)) {
            for (LogRow r : rows) {
                ps.setLong(1, r.id()); ps.setLong(2, r.time());
                setStr(ps, 3, r.type()); setStr(ps, 4, r.player()); setStr(ps, 5, r.uuid()); setStr(ps, 6, r.action());
                setStr(ps, 7, r.data()); setStr(ps, 8, r.world() == null ? "" : r.world());
                ps.setInt(9, r.x()); ps.setInt(10, r.y()); ps.setInt(11, r.z());
                setStr(ps, 12, r.oldData()); setStr(ps, 13, r.oldExtra());
                ps.setInt(14, r.amount()); setStr(ps, 15, r.meta()); ps.setInt(16, r.rolledBack() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ Benutzer / UUID-Zuordnung

    private void loadUsers() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name FROM up_names ORDER BY first_seen")) {
            while (rs.next()) registerName(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load name history: " + e.getMessage());
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT player_name FROM logs WHERE player_name IS NOT NULL LIMIT 20000")) {
            while (rs.next()) {
                String n = rs.getString(1);
                if (n != null && !n.isEmpty()) { knownUsers.add(n); canonical.putIfAbsent(n.toLowerCase(Locale.ROOT), n); }
            }
        } catch (Exception ignored) { /* optionaler Komfort */ }
    }

    private void registerName(String uuid, String name) {
        if (uuid == null || name == null) return;
        String lc = name.toLowerCase(Locale.ROOT);
        nameToUuids.computeIfAbsent(lc, k -> ConcurrentHashMap.newKeySet()).add(uuid);
        uuidToNames.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(name);
        uuidToName.put(uuid, name);
        canonical.put(lc, name);
        knownUsers.add(name);
    }

    /** Beim Join aufrufen: speichert die Namenshistorie und loggt Namensänderungen (a:username). */
    public void playerSeen(UUID id, String name) {
        String u = id.toString();
        String prev = uuidToName.get(u);
        if (name.equals(prev)) return;
        registerName(u, name);
        long now = System.currentTimeMillis();
        submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO up_names (uuid, name, first_seen) VALUES (?,?,?)")) {
                ps.setString(1, u); ps.setString(2, name); ps.setLong(3, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not store name history: " + e.getMessage());
            }
        });
        if (prev != null && plugin.settings().opt("log.usernames", null)) {
            log(new LogEntry(now, "username", name, u, Actions.USERNAME, prev + " -> " + name, "", 0, 0, 0, null, null, 1, null));
        }
    }

    public Set<String> getKnownUsers() { return knownUsers; }

    /** Alle bekannten Namen (aktueller + frühere) einer UUID. */
    public Set<String> namesOf(String uuid) {
        Set<String> s = uuidToNames.get(uuid);
        return s == null ? Set.of() : s;
    }

    public String currentName(String uuid) { return uuidToName.get(uuid); }

    /** Löst einen eingegebenen Namen in UUIDs und alle zugehörigen Namensvarianten auf. */
    private void resolve(String input, Set<String> uuids, Set<String> names) {
        String lc = input.toLowerCase(Locale.ROOT);
        names.add(canonical.getOrDefault(lc, input));
        names.add(input);
        Set<String> us = nameToUuids.get(lc);
        if (us == null) return;
        for (String u : us) {
            uuids.add(u);
            names.addAll(namesOf(u));
        }
    }

    // ------------------------------------------------------------------ Logging

    public void log(LogEntry e) {
        if (!plugin.isConsumerActive()) return;
        if (e.player() != null) knownUsers.add(e.player());
        if (plugin.blacklist().isBlocked(e)) return;
        queue.add(e);
    }

    /** Kompatibilität zur bisherigen Signatur. */
    public void logAction(String type, String playerName, String action, String data, String world, int x, int y, int z, String oldData, String oldExtra) {
        log(LogEntry.of(type, playerName, null, action, data, world, x, y, z).withOld(oldData, oldExtra));
    }

    /** Noch nicht geschriebene Einträge (für queueLookup der API). */
    public List<LogEntry> pending() { return new ArrayList<>(queue); }

    private LogEntry fireEvent(LogEntry e) {
        if (PreLogEvent.getHandlerList().getRegisteredListeners().length == 0) return e;
        try {
            Material mat = null;
            EntityType ent = null;
            String a = e.action();
            if (Actions.BLOCK.contains(a) && e.data() != null && !e.data().startsWith("frame:")) {
                mat = Material.matchMaterial(Actions.blockId(e.data()));
            } else if (e.data() != null && (Actions.KILL.equals(a) || Actions.SPAWN.equals(a))) {
                try { ent = EntityType.valueOf(e.data().split(":")[0].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { /* unbekannt */ }
            }
            PreLogEvent.Action pa = switch (a == null ? "" : a) {
                case Actions.BROKE -> PreLogEvent.Action.BLOCK_BREAK;
                case Actions.PLACED -> PreLogEvent.Action.BLOCK_PLACE;
                case Actions.USE -> PreLogEvent.Action.PLAYER_INTERACTION;
                case Actions.KILL -> (e.data() != null && e.data().contains(":")) ? PreLogEvent.Action.PLAYER_KILL : PreLogEvent.Action.ENTITY_KILL;
                case Actions.SPAWN -> PreLogEvent.Action.ENTITY_SPAWN;
                case Actions.SIGN -> PreLogEvent.Action.SIGN_TEXT;
                case Actions.PUT, Actions.TAKE -> PreLogEvent.Action.CONTAINER_TRANSACTION;
                case Actions.COMMAND -> PreLogEvent.Action.PLAYER_COMMAND;
                default -> Actions.ITEM_ALL.contains(a) ? PreLogEvent.Action.ITEM_TRANSACTION : PreLogEvent.Action.UNKNOWN;
            };
            String msg = Actions.TEXT.contains(a) ? e.data() : null;
            Location loc = new Location(e.world() == null ? null : Bukkit.getWorld(e.world()), e.x(), e.y(), e.z());
            PreLogEvent evt = new PreLogEvent(e.player(), loc, pa, Actions.cpId(a), mat, ent, msg);
            Bukkit.getPluginManager().callEvent(evt);
            if (evt.isCancelled()) return null;
            LogEntry out = e;
            if (evt.getUser() != null && !evt.getUser().equals(e.player())) out = out.withUser(evt.getUser());
            Location nl = evt.getLocation();
            if (nl != null && nl.getWorld() != null) out = out.withLocation(nl.getWorld().getName(), nl.getBlockX(), nl.getBlockY(), nl.getBlockZ());
            return out;
        } catch (Throwable t) {
            return e; // fehlerhafte Listener dürfen das Logging nicht verhindern
        }
    }

    /** Muss auf dem Datenbank-Thread laufen. */
    synchronized void flush() {
        Connection con = connection;
        if (con == null) return;
        List<LogRow> batch = new ArrayList<>(retry);
        LogEntry e;
        while ((e = queue.poll()) != null) {
            LogEntry r = fireEvent(e);
            if (r != null) batch.add(LogRow.fromEntry(nextId.getAndIncrement(), r));
        }
        if (batch.isEmpty()) return;
        try {
            withTx(con, type, () -> insertRows(con, batch));
            retry.clear();
        } catch (SQLException ex) {
            retry.clear();
            retry.addAll(batch);
            plugin.getLogger().warning("Could not write log batch (will retry): " + ex.getMessage());
        }
    }

    /** Wie flush(), aber von beliebigem Thread aufrufbar. */
    public void flushNow() {
        try { call(() -> { flush(); return null; }); } catch (Exception ignored) { /* best effort */ }
    }

    // ------------------------------------------------------------------ Query-Bau

    private static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    private String nameColumn() { return type == DbType.SQLITE ? "player_name COLLATE NOCASE" : "player_name"; }

    private void userClause(StringBuilder sb, List<Object> args, List<String> input, boolean negate) {
        if (input == null || input.isEmpty()) return;
        Set<String> uuids = new LinkedHashSet<>(), names = new LinkedHashSet<>();
        for (String n : input) resolve(n, uuids, names);
        StringBuilder cl = new StringBuilder("(");
        String uc = negate ? "coalesce(uuid, '')" : "uuid";
        String nc = negate ? "coalesce(player_name, '')" : nameColumn();
        if (!uuids.isEmpty()) {
            cl.append(uc).append(" IN (").append(placeholders(uuids.size())).append(")");
            args.addAll(uuids);
            cl.append(" OR ");
        }
        cl.append(nc).append(" IN (").append(placeholders(names.size())).append(")");
        args.addAll(names);
        cl.append(")");
        sb.append(negate ? " AND NOT " : " AND ").append(cl);
    }

    /** Baut die WHERE-Bedingung (ohne das Schlüsselwort WHERE). */
    public String buildWhere(Query q, List<Object> args) {
        DbType t = type;
        StringBuilder sb = new StringBuilder("1=1");
        if (q.type != null) { sb.append(" AND type = ?"); args.add(q.type); }
        userClause(sb, args, q.users, false);
        userClause(sb, args, q.excludeUsers, true);
        if (q.actions != null && !q.actions.isEmpty()) {
            sb.append(" AND action IN (").append(placeholders(q.actions.size())).append(")");
            args.addAll(q.actions);
        }
        if (q.since > 0) { sb.append(" AND time_millis >= ?"); args.add(q.since); }
        if (q.until > 0) { sb.append(" AND time_millis < ?"); args.add(q.until); }
        if (q.worlds != null && !q.worlds.isEmpty()) {
            sb.append(" AND world IN (").append(placeholders(q.worlds.size())).append(")");
            args.addAll(q.worlds);
        }
        if (q.radius >= 0) {
            sb.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            args.add(q.x - q.radius); args.add(q.x + q.radius);
            args.add(q.y - q.radius); args.add(q.y + q.radius);
            args.add(q.z - q.radius); args.add(q.z + q.radius);
        }
        if (!q.include.isEmpty()) {
            sb.append(" AND (");
            for (int i = 0; i < q.include.size(); i++) {
                if (i > 0) sb.append(" OR ");
                sb.append(t.containsSql("data"));
                args.add(t.containsArg(q.include.get(i)));
            }
            sb.append(")");
        }
        for (String ex : q.exclude) {
            sb.append(" AND NOT (").append(t.containsSql("data")).append(")");
            args.add(t.containsArg(ex));
        }
        if (!q.filterInclude.isEmpty()) {
            sb.append(" AND (");
            for (int i = 0; i < q.filterInclude.size(); i++) {
                if (i > 0) sb.append(" OR ");
                sb.append(t.startsSql("data"));
                args.add(t.startsArg(q.filterInclude.get(i)));
            }
            sb.append(")");
        }
        for (String ex : q.filterExclude) {
            sb.append(" AND NOT (").append(t.startsSql("data")).append(")");
            args.add(t.startsArg(ex));
        }
        return sb.toString();
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
    }

    static LogRow map(ResultSet rs) throws SQLException {
        return new LogRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getString(12),
                rs.getString(13), rs.getInt(14), rs.getString(15), rs.getInt(16) != 0);
    }

    /**
     * Liest Zeilen (nur auf dem Datenbank-Thread).
     *
     * @param extra   zusätzliche SQL-Bedingung oder null
     * @param limit   &lt;= 0 = unbegrenzt
     */
    public List<LogRow> selectRows(Query q, boolean ascending, long limit, long offset, String extra, List<Object> extraArgs) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = SELECT_ROWS + buildWhere(q, args) + (extra == null ? "" : " AND " + extra);
        if (extraArgs != null) args.addAll(extraArgs);
        String dir = ascending ? "ASC" : "DESC";
        sql += " ORDER BY time_millis " + dir + ", id " + dir;
        if (limit > 0) { sql += " LIMIT ? OFFSET ?"; args.add(limit); args.add(Math.max(0, offset)); }
        List<LogRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        }
        return out;
    }

    public long count(Query q) throws SQLException {
        List<Object> args = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM logs WHERE " + buildWhere(q, args))) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    // ------------------------------------------------------------------ Lookup

    public Query getLastQuery(CommandSender s) { return lastQueries.get(keyOf(s)); }

    public static UUID keyOf(CommandSender s) { return s instanceof Player p ? p.getUniqueId() : CONSOLE_KEY; }

    public void lookup(CommandSender sender, Query q) {
        submit(() -> {
            try {
                flush();
                if (q.hasTag("summary")) {
                    List<Object> args = new ArrayList<>();
                    String sql = "SELECT player_name, action, COUNT(*) c FROM logs WHERE " + buildWhere(q, args)
                            + " GROUP BY player_name, action ORDER BY c DESC LIMIT 20";
                    List<String[]> rows = new ArrayList<>();
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        bind(ps, args);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getLong(3))});
                        }
                    }
                    LookupView.summary(sender, rows);
                    return;
                }
                long total = count(q);
                if (q.hasTag("count")) { LookupView.count(sender, total); return; }
                if (total == 0) { LookupView.none(sender); return; }

                int lines = Math.max(1, q.lines);
                int pages = (int) ((total + lines - 1) / lines);
                int page = Math.min(Math.max(1, q.page), pages);
                lastQueries.put(keyOf(sender), q.withPage(page));
                List<LogRow> rows = selectRows(q, false, lines, (long) (page - 1) * lines, null, null);
                LookupView.page(sender, q, rows, page, pages, total, this);
                if (sender instanceof Player p && plugin.network() != null) plugin.network().sendResults(p, rows);
            } catch (Exception e) {
                plugin.getLogger().warning("Lookup failed: " + e.getMessage());
                e.printStackTrace();
                Msg.err(sender, "Lookup failed, see console.");
            }
        });
    }

    // ------------------------------------------------------------------ Rolled-back-Markierung

    public void markRolledBack(List<Long> ids, boolean rolled) {
        if (ids == null || ids.isEmpty()) return;
        List<Long> copy = new ArrayList<>(ids);
        submit(() -> {
            if (migrating) flagReplay.add(new Object[]{copy, rolled});
            try { applyFlags(connection, type, copy, rolled); }
            catch (SQLException e) { plugin.getLogger().warning("Could not update rollback flags: " + e.getMessage()); }
        });
    }

    static void applyFlags(Connection c, DbType t, List<Long> ids, boolean rolled) throws SQLException {
        for (int i = 0; i < ids.size(); i += FLAG_CHUNK) {
            StringBuilder sb = new StringBuilder();
            for (Long id : ids.subList(i, Math.min(ids.size(), i + FLAG_CHUNK))) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id.longValue());
            }
            try (Statement st = c.createStatement()) { st.execute(t.flagSql(rolled, sb.toString())); }
        }
    }

    // ------------------------------------------------------------------ Purge / Optimierung / Statistik

    /** Löscht passende Zeilen. Muss auf dem Datenbank-Thread laufen. */
    public long purgeNow(Query q) throws SQLException {
        flush();
        List<Object> args = new ArrayList<>();
        String where = buildWhere(q, args);
        long count;
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM logs WHERE " + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { count = rs.next() ? rs.getLong(1) : 0; }
        }
        if (count == 0) return 0;
        if (type == DbType.CLICKHOUSE) {
            try (Statement st = connection.createStatement()) { st.execute(type.deleteSql(DbType.inline(where, args))); }
        } else {
            try (PreparedStatement ps = connection.prepareStatement(type.deleteSql(where))) {
                bind(ps, args);
                ps.executeUpdate();
            }
        }
        return count;
    }

    public void purge(CommandSender sender, Query q, boolean optimize, boolean silent) {
        submit(() -> {
            if (migrating) { Msg.err(sender, "A database migration is running. Try again afterwards."); return; }
            try {
                long removed = 0;
                if (q.until > 0) {
                    if (!silent) Msg.info(sender, "Purging data... this may take a while.");
                    removed = purgeNow(q);
                }
                if (optimize) {
                    if (!silent) Msg.info(sender, "Optimizing " + type.id() + " database (#optimize)...");
                    type.optimize(connection);
                }
                Msg.ok(sender, (q.until > 0 ? "Purge complete. Removed <white>" + removed + "</white> rows." : "")
                        + (optimize ? (q.until > 0 ? " " : "") + "Database optimized." : ""));
            } catch (Exception e) {
                e.printStackTrace();
                Msg.err(sender, "Purge failed, see console.");
            }
        });
    }

    public void autoPurge(int days) {
        submit(() -> {
            if (migrating) return;
            try {
                Query q = new Query();
                q.until = System.currentTimeMillis() - days * 86_400_000L;
                long del = purgeNow(q);
                if (del > 0) plugin.getLogger().info("Auto-purge removed " + del + " rows older than " + days + " days.");
            } catch (Exception e) {
                plugin.getLogger().warning("Auto-purge failed: " + e.getMessage());
            }
        });
    }

    public void stats(CommandSender sender) {
        submit(() -> {
            try {
                flush();
                long total = 0;
                List<String[]> lines = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT type, COUNT(*) FROM logs GROUP BY type ORDER BY 2 DESC")) {
                    while (rs.next()) {
                        long c = rs.getLong(2);
                        total += c;
                        lines.add(new String[]{rs.getString(1), String.valueOf(c)});
                    }
                }
                long size = type.fileSize(plugin);
                Msg.header(sender, "Statistics");
                Msg.kv(sender, "Engine", type.id());
                Msg.kv(sender, "Rows", String.valueOf(total));
                Msg.kv(sender, "Size", size < 0 ? "n/a (remote)" : String.format(Locale.ROOT, "%.2f MB", size / 1048576.0));
                Msg.kv(sender, "Queue", String.valueOf(queue.size()));
                for (String[] l : lines) Msg.item(sender, Msg.esc(l[0]), l[1]);
                Msg.footer(sender);
            } catch (Exception e) {
                e.printStackTrace();
                Msg.err(sender, "Stats failed, see console.");
            }
        });
    }

    // ------------------------------------------------------------------ Umschalten (Migration) / Beenden

    /** Tauscht die aktive Datenbank. Nur vom Datenbank-Thread aufrufen. */
    void switchTo(DbType newType, Connection newCon, long maxId) {
        Connection old = connection;
        connection = newCon;
        type = newType;
        nextId.set(maxId + 1);
        try { if (old != null) old.close(); } catch (SQLException ignored) { /* nothing to do */ }
    }

    public void close() {
        try {
            executor.shutdown();
            try { executor.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            flush();
            if (connection != null) connection.close();
        } catch (Exception ignored) { /* shutdown */ }
    }

    /** Kleiner Helfer für Weltnamen -> geladene Welt (Hauptthread). */
    public static World world(String name) { return name == null ? null : Bukkit.getWorld(name); }
}
