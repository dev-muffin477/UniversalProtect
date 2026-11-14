package de.muffin.universalprotect;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Migrator {
    private static final int CHUNK = 10_000;

    private final UniversalProtect plugin;
    private final DatabaseManager db;

    public Migrator(UniversalProtect plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    private static final class Ctx {
        final Sender sender;
        final DbType target;
        final boolean full;
        final Connection dst;
        long total, copied, lastId, validatedId, snapshotMax;
        int nextReport = 10;
        Ctx(Sender sender, DbType target, boolean full, Connection dst) {
            this.sender = sender; this.target = target; this.full = full; this.dst = dst;
        }
    }

    public void start(Sender sender, DbType target, boolean fullValidation) {
        if (db.isMigrating()) { Msg.err(sender, "A migration is already running."); return; }
        if (target == db.type()) { Msg.err(sender, "The server already uses " + target.id() + "."); return; }
        db.submit(() -> begin(sender, target, fullValidation));
    }

    private void begin(Sender sender, DbType target, boolean full) {
        Connection dst = null;
        try {
            db.flush();
            Msg.info(sender, "Connecting to " + target.id() + " (configured in config.yml, section database." + target.id() + ")...");
            dst = target.connect(plugin);
            if (scalar(dst, "SELECT COUNT(*) FROM logs") > 0) {
                dst.close();
                Msg.err(sender, "The target database already contains data. Use an empty database for the migration.");
                return;
            }
            Ctx c = new Ctx(sender, target, full, dst);
            c.total = scalar(db.connection(), "SELECT COUNT(*) FROM logs");
            db.setMigrating(true);
            Msg.ok(sender, "Migration " + db.type().id() + " → " + target.id() + " started: <white>" + c.total + "</white> rows. Logging continues normally.");
            db.submit(() -> copyChunk(c));
        } catch (Exception e) {
            if (dst != null) { try { dst.close(); } catch (SQLException ignored) {  } }
            db.setMigrating(false);
            plugin.getLogger().warning("Migration could not be started: " + e);
            Msg.err(sender, "Migration could not be started: " + Msg.esc(String.valueOf(e.getMessage())));
        }
    }

    private void copyChunk(Ctx c) {
        try {
            List<LogRow> rows = read(db.connection(), c.lastId, Long.MAX_VALUE, CHUNK);
            if (rows.isEmpty()) {
                c.snapshotMax = c.lastId;
                db.submit(() -> validateChunk(c));
                return;
            }
            db.withTx(c.dst, c.target, () -> DatabaseManager.insertRows(c.dst, rows));
            c.lastId = rows.get(rows.size() - 1).id();
            c.copied += rows.size();
            int pct = c.total == 0 ? 100 : (int) (c.copied * 100 / c.total);
            if (pct >= c.nextReport) {
                c.nextReport = pct + 10;
                Msg.info(c.sender, "Migration progress: <white>" + Math.min(100, pct) + "%</white> (" + c.copied + "/" + c.total + ")");
            }
            db.submit(() -> copyChunk(c));
        } catch (Exception e) {
            fail(c, e);
        }
    }

    private void validateChunk(Ctx c) {
        if (!c.full) { db.submit(() -> finish(c)); return; }
        try {
            List<LogRow> a = read(db.connection(), c.validatedId, c.snapshotMax, CHUNK);
            List<LogRow> b = read(c.dst, c.validatedId, c.snapshotMax, CHUNK);
            if (a.size() != b.size()) throw new IllegalStateException("Row count mismatch after id " + c.validatedId);
            for (int i = 0; i < a.size(); i++) {
                if (!fingerprint(a.get(i)).equals(fingerprint(b.get(i)))) {
                    throw new IllegalStateException("Data mismatch at row id " + a.get(i).id());
                }
            }
            if (a.isEmpty()) {
                Msg.info(c.sender, "Full validation passed.");
                db.submit(() -> finish(c));
                return;
            }
            c.validatedId = a.get(a.size() - 1).id();
            db.submit(() -> validateChunk(c));
        } catch (Exception e) {
            fail(c, e);
        }
    }

    private void finish(Ctx c) {
        try {
            db.flush();
            Connection src = db.connection();
            while (true) {
                List<LogRow> rows = read(src, c.lastId, Long.MAX_VALUE, CHUNK);
                if (rows.isEmpty()) break;
                db.withTx(c.dst, c.target, () -> DatabaseManager.insertRows(c.dst, rows));
                c.lastId = rows.get(rows.size() - 1).id();
                c.copied += rows.size();
            }
            try (Statement st = c.dst.createStatement()) {
                st.execute(c.target == DbType.CLICKHOUSE ? "TRUNCATE TABLE up_names" : "DELETE FROM up_names");
            }
            List<String[]> names = new ArrayList<>();
            try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, name, first_seen FROM up_names")) {
                while (rs.next()) names.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getLong(3))});
            }
            db.withTx(c.dst, c.target, () -> {
                try (PreparedStatement ps = c.dst.prepareStatement("INSERT INTO up_names (uuid, name, first_seen) VALUES (?,?,?)")) {
                    for (String[] n : names) { ps.setString(1, n[0]); ps.setString(2, n[1]); ps.setLong(3, Long.parseLong(n[2])); ps.addBatch(); }
                    ps.executeBatch();
                }
            });
            for (Object[] f : new ArrayList<>(db.flagReplay())) {
                @SuppressWarnings("unchecked") List<Long> ids = (List<Long>) f[0];
                DatabaseManager.applyFlags(c.dst, c.target, ids, (Boolean) f[1]);
            }

            long srcRows = scalar(src, "SELECT COUNT(*) FROM logs"), dstRows = scalar(c.dst, "SELECT COUNT(*) FROM logs");
            long srcMax = scalar(src, "SELECT MAX(id) FROM logs"), dstMax = scalar(c.dst, "SELECT MAX(id) FROM logs");
            if (srcRows != dstRows || srcMax != dstMax) {
                throw new IllegalStateException("Validation failed: source " + srcRows + " rows / max id " + srcMax
                        + ", target " + dstRows + " rows / max id " + dstMax);
            }

            DbType old = db.type();
            db.switchTo(c.target, c.dst, dstMax);
            db.setMigrating(false);
            Sched.main(() -> {
                plugin.saveDatabaseType(c.target.id());
            });
            plugin.getLogger().info("Database migration " + old.id() + " -> " + c.target.id() + " completed (" + dstRows + " rows).");
            Msg.header(c.sender, "Migration complete");
            Msg.kv(c.sender, "From", old.id());
            Msg.kv(c.sender, "To", c.target.id() + " <gray>(now active, saved in config.yml)</gray>");
            Msg.kv(c.sender, "Rows", String.valueOf(dstRows));
            Msg.kv(c.sender, "Validation", c.full ? "full row-by-row" : "row count + max id");
            Msg.footer(c.sender);
        } catch (Exception e) {
            fail(c, e);
        }
    }

    private void fail(Ctx c, Exception e) {
        db.setMigrating(false);
        try { c.dst.close(); } catch (SQLException ignored) {  }
        plugin.getLogger().warning("Migration failed: " + e);
        e.printStackTrace();
        Msg.err(c.sender, "Migration failed, the current database stays active: " + Msg.esc(String.valueOf(e.getMessage())));
    }

    private static List<LogRow> read(Connection con, long afterId, long maxId, int limit) throws SQLException {
        List<LogRow> out = new ArrayList<>();
        String sql = "SELECT " + DbType.COLS + " FROM logs WHERE id > ?" + (maxId == Long.MAX_VALUE ? "" : " AND id <= ?") + " ORDER BY id LIMIT ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, afterId);
            if (maxId != Long.MAX_VALUE) ps.setLong(i++, maxId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(DatabaseManager.map(rs)); }
        }
        return out;
    }

    private static long scalar(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : 0; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String fingerprint(LogRow r) {
        return r.id() + "\u0001" + r.time() + "\u0001" + nz(r.type()) + "\u0001" + nz(r.player()) + "\u0001" + nz(r.uuid()) + "\u0001"
                + nz(r.action()) + "\u0001" + nz(r.data()) + "\u0001" + nz(r.world()) + "\u0001" + r.x() + "\u0001" + r.y() + "\u0001" + r.z()
                + "\u0001" + nz(r.oldData()) + "\u0001" + nz(r.oldExtra()) + "\u0001" + r.amount() + "\u0001" + nz(r.meta()) + "\u0001" + r.rolledBack();
    }
}
