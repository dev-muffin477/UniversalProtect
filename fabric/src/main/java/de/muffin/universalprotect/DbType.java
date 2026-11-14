package de.muffin.universalprotect;

import java.io.File;
import java.sql.*;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public enum DbType {
    SQLITE("sqlite"), MYSQL("mysql"), DUCKDB("duckdb"), CLICKHOUSE("clickhouse");

    public static final List<String> NAMES = List.of("sqlite", "mysql", "duckdb", "clickhouse");
    public static final String COLS = "id, time_millis, type, player_name, uuid, action, data, world, x, y, z, old_data, old_extra, amount, meta, rolled_back";
    public static final String INSERT_SQL = "INSERT INTO logs (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final String id;

    DbType(String id) { this.id = id; }

    public String id() { return id; }

    public static DbType parse(String s) {
        if (s == null) return null;
        String l = s.trim().toLowerCase(Locale.ROOT);
        for (DbType t : values()) if (t.id.equals(l)) return t;
        return null;
    }

    public Connection connect(UniversalProtect plugin) throws Exception {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        Cfg c = plugin.config().getConfigurationSection("database." + id);
        Connection con;
        switch (this) {
            case SQLITE -> {
                Class.forName("org.sqlite.JDBC");
                String file = c == null ? "database.db" : c.getString("file", "database.db");
                con = DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), file).getAbsolutePath());
                try (Statement st = con.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                }
            }
            case DUCKDB -> {
                Class.forName("org.duckdb.DuckDBDriver");
                String file = c == null ? "database.duckdb" : c.getString("file", "database.duckdb");
                con = DriverManager.getConnection("jdbc:duckdb:" + new File(plugin.getDataFolder(), file).getAbsolutePath());
            }
            case MYSQL -> {
                Class.forName("com.mysql.cj.jdbc.Driver");
                String host = c == null ? "localhost" : c.getString("host", "localhost");
                int port = c == null ? 3306 : c.getInt("port", 3306);
                String db = c == null ? "universalprotect" : c.getString("database", "universalprotect");
                String params = c == null ? "" : c.getString("parameters", "");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?createDatabaseIfNotExist=true&characterEncoding=utf8&rewriteBatchedStatements=true"
                        + (params == null || params.isBlank() ? "" : "&" + params);
                con = DriverManager.getConnection(url, c == null ? "root" : c.getString("username", "root"),
                        c == null ? "" : c.getString("password", ""));
            }
            case CLICKHOUSE -> {
                Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
                String host = c == null ? "localhost" : c.getString("host", "localhost");
                int port = c == null ? 8123 : c.getInt("port", 8123);
                String db = c == null ? "universalprotect" : c.getString("database", "universalprotect");
                boolean ssl = c != null && c.getBoolean("ssl", false);
                Properties p = new Properties();
                p.setProperty("user", c == null ? "default" : c.getString("username", "default"));
                p.setProperty("password", c == null ? "" : c.getString("password", ""));
                p.setProperty("compress", "0");
                p.setProperty("decompress", "0");
                if (ssl) p.setProperty("ssl", "true");
                String base = "jdbc:clickhouse://" + host + ":" + port + "/";
                try (Connection boot = DriverManager.getConnection(base + "default", p);
                     Statement st = boot.createStatement()) {
                    st.execute("CREATE DATABASE IF NOT EXISTS `" + db.replace("`", "") + "`");
                }
                con = DriverManager.getConnection(base + db, p);
            }
            default -> throw new IllegalStateException("Unknown database type");
        }
        initSchema(con);
        return con;
    }

    private void initSchema(Connection con) throws SQLException {
        try (Statement st = con.createStatement()) {
            switch (this) {
                case SQLITE -> {
                    st.execute("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY, time_millis INTEGER, type VARCHAR(16), player_name VARCHAR(64) COLLATE NOCASE, uuid VARCHAR(36), action VARCHAR(16), data TEXT, world VARCHAR(64), x INTEGER, y INTEGER, z INTEGER, old_data TEXT, old_extra TEXT, amount INTEGER DEFAULT 1, meta VARCHAR(64), rolled_back INTEGER DEFAULT 0)");
                    addMissingColumns(con, st, new String[][]{{"uuid", "VARCHAR(36)"}, {"amount", "INTEGER DEFAULT 1"}, {"meta", "VARCHAR(64)"}, {"rolled_back", "INTEGER DEFAULT 0"}});
                    st.execute("CREATE INDEX IF NOT EXISTS idx_logs_pos ON logs (world, x, z, y)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_logs_time ON logs (time_millis)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_logs_player ON logs (player_name COLLATE NOCASE)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_logs_uuid ON logs (uuid)");
                    st.execute("CREATE TABLE IF NOT EXISTS up_names (uuid VARCHAR(36), name VARCHAR(64), first_seen INTEGER)");
                }
                case MYSQL -> {
                    st.execute("CREATE TABLE IF NOT EXISTS logs (id BIGINT NOT NULL PRIMARY KEY, time_millis BIGINT NOT NULL, type VARCHAR(16), player_name VARCHAR(64), uuid VARCHAR(36), action VARCHAR(16), data TEXT, world VARCHAR(64), x INT, y INT, z INT, old_data TEXT, old_extra MEDIUMTEXT, amount INT DEFAULT 1, meta VARCHAR(64), rolled_back TINYINT DEFAULT 0, INDEX idx_logs_pos (world, x, z, y), INDEX idx_logs_time (time_millis), INDEX idx_logs_player (player_name), INDEX idx_logs_uuid (uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                    st.execute("CREATE TABLE IF NOT EXISTS up_names (uuid VARCHAR(36), name VARCHAR(64), first_seen BIGINT, INDEX idx_names_uuid (uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                }
                case DUCKDB -> {
                    st.execute("CREATE TABLE IF NOT EXISTS logs (id BIGINT, time_millis BIGINT, type VARCHAR, player_name VARCHAR, uuid VARCHAR, action VARCHAR, data VARCHAR, world VARCHAR, x INTEGER, y INTEGER, z INTEGER, old_data VARCHAR, old_extra VARCHAR, amount INTEGER DEFAULT 1, meta VARCHAR, rolled_back TINYINT DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_logs_pos ON logs (world, x, z, y)");
                    st.execute("CREATE TABLE IF NOT EXISTS up_names (uuid VARCHAR, name VARCHAR, first_seen BIGINT)");
                }
                case CLICKHOUSE -> {
                    st.execute("CREATE TABLE IF NOT EXISTS logs (id UInt64, time_millis Int64, type LowCardinality(String), player_name Nullable(String), uuid Nullable(String), action LowCardinality(String), data Nullable(String), world String, x Int32, y Int32, z Int32, old_data Nullable(String), old_extra Nullable(String), amount Int32 DEFAULT 1, meta Nullable(String), rolled_back UInt8 DEFAULT 0) ENGINE = MergeTree ORDER BY (world, x, z, y, time_millis, id)");
                    st.execute("CREATE TABLE IF NOT EXISTS up_names (uuid String, name String, first_seen Int64) ENGINE = MergeTree ORDER BY (uuid, name)");
                }
            }
        }
    }

    private static void addMissingColumns(Connection con, Statement st, String[][] cols) throws SQLException {
        DatabaseMetaData md = con.getMetaData();
        for (String[] col : cols) {
            boolean exists;
            try (ResultSet rs = md.getColumns(null, null, "logs", col[0])) { exists = rs.next(); }
            if (!exists) st.execute("ALTER TABLE logs ADD COLUMN " + col[0] + " " + col[1]);
        }
    }

    public String containsSql(String col) {
        return this == CLICKHOUSE ? "positionCaseInsensitiveUTF8(coalesce(" + col + ", ''), ?) > 0"
                : "LOWER(" + col + ") LIKE ? ESCAPE '!'";
    }

    public String containsArg(String text) {
        return this == CLICKHOUSE ? text : "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
    }

    public String startsSql(String col) {
        return this == CLICKHOUSE ? "startsWith(lower(coalesce(" + col + ", '')), ?)"
                : "LOWER(" + col + ") LIKE ? ESCAPE '!'";
    }

    public String startsArg(String text) {
        return this == CLICKHOUSE ? text.toLowerCase(Locale.ROOT) : escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public String deleteSql(String where) {
        return this == CLICKHOUSE ? "ALTER TABLE logs DELETE WHERE " + where + " SETTINGS mutations_sync = 1"
                : "DELETE FROM logs WHERE " + where;
    }

    public String flagSql(boolean rolled, String idList) {
        int v = rolled ? 1 : 0;
        return this == CLICKHOUSE ? "ALTER TABLE logs UPDATE rolled_back = " + v + " WHERE id IN (" + idList + ") SETTINGS mutations_sync = 1"
                : "UPDATE logs SET rolled_back = " + v + " WHERE id IN (" + idList + ")";
    }

    public void optimize(Connection con) throws SQLException {
        try (Statement st = con.createStatement()) {
            switch (this) {
                case SQLITE -> {
                    try { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (SQLException ignored) {  }
                    st.execute("VACUUM");
                }
                case MYSQL -> st.execute("OPTIMIZE TABLE logs");
                case DUCKDB -> {
                    st.execute("CHECKPOINT");
                    try { st.execute("VACUUM"); } catch (SQLException ignored) {  }
                }
                case CLICKHOUSE -> st.execute("OPTIMIZE TABLE logs FINAL");
            }
        }
    }

    public static String inline(String sql, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        int ai = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?' && ai < args.size()) {
                Object o = args.get(ai++);
                if (o instanceof Number || o instanceof Boolean) sb.append(o);
                else sb.append('\'').append(String.valueOf(o).replace("\\", "\\\\").replace("'", "\\'")).append('\'');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    public long fileSize(UniversalProtect plugin) {
        Cfg c = plugin.config().getConfigurationSection("database." + id);
        String name = switch (this) {
            case SQLITE -> c == null ? "database.db" : c.getString("file", "database.db");
            case DUCKDB -> c == null ? "database.duckdb" : c.getString("file", "database.duckdb");
            default -> null;
        };
        if (name == null) return -1;
        File f = new File(plugin.getDataFolder(), name);
        long size = f.length();
        File wal = new File(f.getPath() + "-wal");
        if (wal.exists()) size += wal.length();
        return size;
    }
}
