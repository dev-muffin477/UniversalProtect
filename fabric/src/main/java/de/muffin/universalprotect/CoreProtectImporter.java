package de.muffin.universalprotect;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

public final class CoreProtectImporter {

    public interface Reporter {
        void info(String message);
        void ok(String message);
        void err(String message);
    }

    static final String TAG = "cp-import";
    private static final int CHUNK = 5_000;
    private static final String AIR = "minecraft:air";

    private final DatabaseManager db;
    private final Logger log;
    private final UnaryOperator<String> worldMapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public CoreProtectImporter(DatabaseManager db, Logger log, UnaryOperator<String> worldMapper) {
        this.db = db;
        this.log = log;
        this.worldMapper = worldMapper;
    }

    public boolean isRunning() { return running.get(); }

    public static final String USAGE = "Usage: /up import-coreprotect sqlite [file]  |  /up import-coreprotect mysql <host[:port]> <database> <user> [password]"
            + "  (options: --prefix=co_  --items  --force)";

    public void start(Reporter rep, String[] args) {
        if (db.isMigrating()) { rep.err("A database migration is running. Try again afterwards."); return; }
        List<String> pos = new ArrayList<>();
        boolean force = false, items = false;
        String prefix = "co_";
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--force")) force = true;
            else if (a.equalsIgnoreCase("--items")) items = true;
            else if (a.toLowerCase(Locale.ROOT).startsWith("--prefix=")) prefix = a.substring(9);
            else pos.add(a);
        }
        if (!prefix.matches("[A-Za-z0-9_]*")) { rep.err("Invalid table prefix."); return; }
        if (pos.isEmpty()) { rep.err(USAGE); return; }

        String mode = pos.get(0).toLowerCase(Locale.ROOT);
        final String url;
        final String user;
        final String password;
        switch (mode) {
            case "sqlite" -> {
                String path = pos.size() > 1 ? pos.get(1) : "plugins/CoreProtect/database.db";
                File f = new File(path);
                if (!f.isFile()) { rep.err("CoreProtect database not found: " + path); return; }
                url = "jdbc:sqlite:" + f.getAbsolutePath();
                user = null;
                password = null;
            }
            case "mysql" -> {
                if (pos.size() < 4) { rep.err(USAGE); return; }
                String hostPort = pos.get(1);
                String host = hostPort, port = "3306";
                int c = hostPort.lastIndexOf(':');
                if (c > 0) { host = hostPort.substring(0, c); port = hostPort.substring(c + 1); }
                if (!host.matches("[A-Za-z0-9._-]+") || !port.matches("\\d+") || !pos.get(2).matches("[A-Za-z0-9_$-]+")) {
                    rep.err("Invalid host, port or database name.");
                    return;
                }
                url = "jdbc:mysql://" + host + ":" + port + "/" + pos.get(2) + "?characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
                user = pos.get(3);
                password = pos.size() > 4 ? pos.get(4) : "";
            }
            default -> { rep.err(USAGE); return; }
        }
        if (!running.compareAndSet(false, true)) { rep.err("An import is already running."); return; }

        final String fPrefix = prefix;
        final boolean fForce = force, fItems = items;
        Thread t = new Thread(() -> {
            try {
                run(rep, mode, url, user, password, fPrefix, fForce, fItems);
            } catch (Throwable e) {
                log.warning("CoreProtect import failed: " + e);
                rep.err("Import failed: " + e.getMessage() + " (see console)");
            } finally {
                running.set(false);
            }
        }, "UniversalProtect-Import");
        t.setDaemon(true);
        t.start();
    }

    private static final class Ctx {
        final Connection src;
        final String prefix;
        final Reporter rep;
        final boolean items;
        Map<Long, String[]> users = new HashMap<>();
        Map<Long, String> worlds = new HashMap<>();
        Map<Long, String> materials = new HashMap<>();
        Map<Long, String> blockdata = new HashMap<>();
        Map<Long, String> entities = new HashMap<>();
        long imported, skipped;

        Ctx(Connection src, String prefix, Reporter rep, boolean items) {
            this.src = src; this.prefix = prefix; this.rep = rep; this.items = items;
        }

        String userName(long id) { String[] u = users.get(id); return u == null || u[0] == null ? "#unknown" : u[0]; }
        String userUuid(long id) { String[] u = users.get(id); return u == null ? null : u[1]; }
    }

    private void run(Reporter rep, String mode, String url, String user, String password, String prefix, boolean force, boolean items) throws Exception {
        rep.info("Opening the CoreProtect " + mode + " database (read only)...");
        Connection src;
        if (mode.equals("mysql")) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            src = DriverManager.getConnection(url, user, password);
        } else {
            Class.forName("org.sqlite.JDBC");
            src = DriverManager.getConnection(url);
        }
        try (Connection c = src) {
            if (!force && alreadyImported()) {
                rep.err("This database already contains imported CoreProtect data. Use --force to import again (this creates duplicates).");
                return;
            }
            Ctx x = new Ctx(c, prefix, rep, items);
            loadMaps(x);
            rep.info("Loaded " + x.users.size() + " users, " + x.worlds.size() + " worlds, " + x.materials.size() + " materials.");

            copyTable(x, "block", "blocks", (r, k) -> mapBlock(r, k));
            copyTable(x, "container", "containers", (r, k) -> mapContainer(r, k));
            copyTable(x, "sign", "signs", (r, k) -> mapSign(r, k));
            copyTable(x, "chat_log", "chat", (r, k) -> mapText(r, k, "chat", Actions.CHAT));
            copyTable(x, "command", "commands", (r, k) -> mapText(r, k, "command", Actions.COMMAND));
            copyTable(x, "session", "sessions", (r, k) -> mapSession(r, k));
            if (items) copyTable(x, "item", "items", (r, k) -> mapItem(r, k));
            else rep.info("Item and inventory history skipped (add --items to include item rows).");
            importNames(x);

            rep.ok("CoreProtect import finished: <white>" + x.imported + "</white> rows imported, " + x.skipped
                    + " skipped. Restart the server so the name history is loaded. Container and entity rows are searchable but cannot be rolled back.");
        }
    }

    private boolean alreadyImported() throws Exception {
        return db.call(() -> {
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM logs WHERE meta = '" + TAG + "'")) {
                return rs.next() && rs.getLong(1) > 0;
            }
        });
    }

    private static final class Cols {
        private final Map<String, Integer> idx = new HashMap<>();
        private final ResultSet rs;

        Cols(ResultSet rs) throws SQLException {
            this.rs = rs;
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) idx.putIfAbsent(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
        }

        long lng(String name, long def) throws SQLException {
            Integer i = idx.get(name);
            if (i == null) return def;
            long v = rs.getLong(i);
            return rs.wasNull() ? def : v;
        }

        String str(String name) throws SQLException {
            Integer i = idx.get(name);
            return i == null ? null : rs.getString(i);
        }

        byte[] bytes(String name) throws SQLException {
            Integer i = idx.get(name);
            return i == null ? null : rs.getBytes(i);
        }
    }

    private interface RowMapper { LogRow map(Cols r, Ctx x) throws SQLException; }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static Map<Long, String> loadMap(Connection c, String table, String idCol, String valCol) {
        Map<Long, String> m = new HashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT " + idCol + ", " + valCol + " FROM " + table)) {
            while (rs.next()) m.put(rs.getLong(1), rs.getString(2));
        } catch (SQLException ignored) {  }
        return m;
    }

    private void loadMaps(Ctx x) {
        x.worlds = loadMap(x.src, x.prefix + "world", "id", "world");
        x.materials = loadMap(x.src, x.prefix + "material_map", "id", "material");
        x.blockdata = loadMap(x.src, x.prefix + "blockdata_map", "id", "data");
        x.entities = loadMap(x.src, x.prefix + "entity_map", "id", "entity");
        try (Statement st = x.src.createStatement(); ResultSet rs = st.executeQuery("SELECT rowid, user, uuid FROM " + x.prefix + "user")) {
            while (rs.next()) x.users.put(rs.getLong(1), new String[]{rs.getString(2), rs.getString(3)});
        } catch (SQLException e) {
            x.rep.err("Could not read the user table: " + e.getMessage());
        }
    }

    private void copyTable(Ctx x, String table, String label, RowMapper mapper) throws Exception {
        String t = x.prefix + table;
        long total;
        try {
            total = scalar(x.src, "SELECT COUNT(*) FROM " + t);
        } catch (SQLException e) {
            x.rep.info("Table " + t + " not found, " + label + " skipped.");
            return;
        }
        if (total == 0) return;
        x.rep.info("Importing " + label + ": " + total + " rows...");
        long last = 0, done = 0, kept = 0;
        int nextPct = 25;
        try (PreparedStatement ps = x.src.prepareStatement(
                "SELECT rowid AS up_rid, * FROM " + t + " WHERE rowid > ? ORDER BY rowid LIMIT " + CHUNK)) {
            while (true) {
                ps.setLong(1, last);
                List<LogRow> batch = new ArrayList<>();
                int n = 0;
                long maxRid = last;
                try (ResultSet rs = ps.executeQuery()) {
                    Cols cols = new Cols(rs);
                    while (rs.next()) {
                        n++;
                        maxRid = Math.max(maxRid, rs.getLong(1));
                        LogRow row;
                        try {
                            row = mapper.map(cols, x);
                        } catch (RuntimeException e) {
                            row = null;
                        }
                        if (row == null) x.skipped++; else batch.add(row);
                    }
                }
                if (n == 0) break;
                write(batch);
                kept += batch.size();
                x.imported += batch.size();
                last = maxRid;
                done += n;
                int pct = (int) (done * 100 / total);
                if (pct >= nextPct && pct < 100) { nextPct = pct + 25; x.rep.info(label + ": " + pct + "%"); }
            }
        }
        x.rep.info(label + " done: " + kept + " imported.");
    }

    private void write(List<LogRow> rows) throws Exception {
        if (rows.isEmpty()) return;
        db.call(() -> {
            long base = db.nextId().getAndAdd(rows.size());
            List<LogRow> out = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                LogRow r = rows.get(i);
                out.add(new LogRow(base + i, r.time(), r.type(), r.player(), r.uuid(), r.action(), r.data(), r.world(),
                        r.x(), r.y(), r.z(), r.oldData(), r.oldExtra(), r.amount(), r.meta(), r.rolledBack()));
            }
            Connection con = db.connection();
            db.withTx(con, db.type(), () -> DatabaseManager.insertRows(con, out));
            return null;
        });
    }

    private String world(Ctx x, long wid) {
        String w = x.worlds.get(wid);
        return w == null ? "" : worldMapper.apply(w);
    }

    private static String ns(String s) {
        if (s == null || s.isEmpty()) return null;
        String l = s.toLowerCase(Locale.ROOT);
        return l.indexOf(':') >= 0 ? l : "minecraft:" + l;
    }

    private static String bare(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("minecraft:") ? l.substring(10) : l;
    }

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static String state(Ctx x, String material, byte[] blob) {
        if (blob == null || blob.length == 0) return material;
        try {
            String[] ids = new String(blob, java.nio.charset.StandardCharsets.UTF_8).split(",");
            StringBuilder sb = new StringBuilder();
            for (String id : ids) {
                if (id.isBlank()) continue;
                String prop = x.blockdata.get(Long.parseLong(id.trim()));
                if (prop == null || prop.isBlank()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(prop);
            }
            return sb.length() == 0 ? material : material + "[" + sb + "]";
        } catch (RuntimeException e) {
            return material;
        }
    }

    private LogRow mapBlock(Cols r, Ctx x) throws SQLException {
        int action = (int) r.lng("action", -1);
        long uid = r.lng("user", -1);
        long type = r.lng("type", -1);
        long time = r.lng("time", 0) * 1000L;
        String w = world(x, r.lng("wid", -1));
        int bx = (int) r.lng("x", 0), by = (int) r.lng("y", 0), bz = (int) r.lng("z", 0);
        boolean rolled = r.lng("rolled_back", 0) != 0;
        String name = x.userName(uid), uuid = x.userUuid(uid);
        switch (action) {
            case 0, 1 -> {
                String mat = ns(x.materials.get(type));
                if (mat == null || isAir(mat)) return null;
                String st = state(x, mat, r.bytes("blockdata"));
                if (action == 0) return new LogRow(0, time, "block", name, uuid, Actions.BROKE, st, w, bx, by, bz, st, null, 1, TAG, rolled);
                return new LogRow(0, time, "block", name, uuid, Actions.PLACED, st, w, bx, by, bz, AIR, null, 1, TAG, rolled);
            }
            case 2 -> {
                String mat = ns(x.materials.get(type));
                if (mat == null) return null;
                return new LogRow(0, time, "interact", name, uuid, Actions.USE, bare(mat), w, bx, by, bz, null, null, 1, TAG, false);
            }
            case 3 -> {
                String ent = bare(x.entities.get(type));
                if (ent.isEmpty()) return null;
                String data = ent;
                if (ent.equals("player")) {
                    long victim = r.lng("data", -1);
                    data = "player:" + (victim >= 0 && x.users.containsKey(victim) ? x.userName(victim) : "unknown");
                }
                return new LogRow(0, time, "kill", name, uuid, Actions.KILL, data, w, bx, by, bz, null, null, 1, TAG, false);
            }
            default -> { return null; }
        }
    }

    private LogRow mapContainer(Cols r, Ctx x) throws SQLException {
        int action = (int) r.lng("action", -1);
        if (action != 0 && action != 1) return null;
        String mat = ns(x.materials.get(r.lng("type", -1)));
        if (mat == null) return null;
        int amount = (int) Math.max(1, r.lng("amount", 1));
        long uid = r.lng("user", -1);
        return new LogRow(0, r.lng("time", 0) * 1000L, "container", x.userName(uid), x.userUuid(uid),
                action == 1 ? Actions.PUT : Actions.TAKE, bare(mat) + " x" + amount, world(x, r.lng("wid", -1)),
                (int) r.lng("x", 0), (int) r.lng("y", 0), (int) r.lng("z", 0), null, null, amount, TAG, r.lng("rolled_back", 0) != 0);
    }

    private LogRow mapSign(Cols r, Ctx x) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            String l = r.str("line_" + i);
            if (l == null || l.isBlank()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(l.trim());
        }
        if (sb.length() == 0) return null;
        long uid = r.lng("user", -1);
        return new LogRow(0, r.lng("time", 0) * 1000L, "sign", x.userName(uid), x.userUuid(uid), Actions.SIGN, sb.toString(),
                world(x, r.lng("wid", -1)), (int) r.lng("x", 0), (int) r.lng("y", 0), (int) r.lng("z", 0), null, null, 1, TAG, false);
    }

    private LogRow mapText(Cols r, Ctx x, String type, String action) throws SQLException {
        String msg = r.str("message");
        if (msg == null || msg.isEmpty()) return null;
        long uid = r.lng("user", -1);
        return new LogRow(0, r.lng("time", 0) * 1000L, type, x.userName(uid), x.userUuid(uid), action, msg,
                world(x, r.lng("wid", -1)), (int) r.lng("x", 0), (int) r.lng("y", 0), (int) r.lng("z", 0), null, null, 1, TAG, false);
    }

    private LogRow mapSession(Cols r, Ctx x) throws SQLException {
        long a = r.lng("action", -1);
        if (a != 0 && a != 1) return null;
        long uid = r.lng("user", -1);
        return new LogRow(0, r.lng("time", 0) * 1000L, "session", x.userName(uid), x.userUuid(uid),
                a == 1 ? Actions.JOIN : Actions.QUIT, a == 1 ? "Login" : "Logout", world(x, r.lng("wid", -1)),
                (int) r.lng("x", 0), (int) r.lng("y", 0), (int) r.lng("z", 0), null, null, 1, TAG, false);
    }

    private LogRow mapItem(Cols r, Ctx x) throws SQLException {
        String action = switch ((int) r.lng("action", -1)) {
            case 2 -> Actions.DROP;
            case 3 -> Actions.PICKUP;
            case 4 -> Actions.THROW;
            case 5 -> Actions.SHOOT;
            case 6 -> Actions.TOOL_BREAK;
            case 7 -> Actions.DESTROY;
            case 8 -> Actions.CREATE;
            case 9 -> Actions.SELL;
            case 10 -> Actions.BUY;
            default -> null;
        };
        if (action == null) return null;
        String mat = ns(x.materials.get(r.lng("type", -1)));
        if (mat == null) return null;
        int amount = (int) Math.max(1, r.lng("amount", 1));
        long uid = r.lng("user", -1);
        return new LogRow(0, r.lng("time", 0) * 1000L, "item", x.userName(uid), x.userUuid(uid), action, bare(mat) + " x" + amount,
                world(x, r.lng("wid", -1)), (int) r.lng("x", 0), (int) r.lng("y", 0), (int) r.lng("z", 0), null, null, amount, TAG, false);
    }

    private void importNames(Ctx x) throws Exception {
        List<String[]> names = new ArrayList<>();
        try (Statement st = x.src.createStatement(); ResultSet rs = st.executeQuery("SELECT time, user, uuid FROM " + x.prefix + "user")) {
            while (rs.next()) {
                String uuid = rs.getString(3), name = rs.getString(2);
                if (uuid != null && uuid.length() == 36 && name != null) names.add(new String[]{uuid, name, String.valueOf(rs.getLong(1) * 1000L)});
            }
        } catch (SQLException ignored) {  }
        try (Statement st = x.src.createStatement(); ResultSet rs = st.executeQuery("SELECT time, user, uuid FROM " + x.prefix + "username_log")) {
            while (rs.next()) {
                String uuid = rs.getString(3), name = rs.getString(2);
                if (uuid != null && uuid.length() == 36 && name != null) names.add(new String[]{uuid, name, String.valueOf(rs.getLong(1) * 1000L)});
            }
        } catch (SQLException ignored) {  }
        if (names.isEmpty()) return;
        db.call(() -> {
            Connection con = db.connection();
            Set<String> have = new HashSet<>();
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, name FROM up_names")) {
                while (rs.next()) have.add(rs.getString(1) + "|" + rs.getString(2));
            }
            List<String[]> add = new ArrayList<>();
            for (String[] n : names) if (have.add(n[0] + "|" + n[1])) add.add(n);
            if (add.isEmpty()) return null;
            db.withTx(con, db.type(), () -> {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO up_names (uuid, name, first_seen) VALUES (?,?,?)")) {
                    for (String[] n : add) { ps.setString(1, n[0]); ps.setString(2, n[1]); ps.setLong(3, Long.parseLong(n[2])); ps.addBatch(); }
                    ps.executeBatch();
                }
            });
            return null;
        });
    }
}
