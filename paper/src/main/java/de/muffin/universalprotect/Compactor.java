package de.muffin.universalprotect;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class Compactor {
    private static final int BATCH = 20_000;
    private static final int MIN_GROUP = 3;
    private static final long DAY = 86_400_000L;
    private static final int DELETE_CHUNK = 1000;

    private static final String SELECT = "SELECT " + DbType.COLS + " FROM logs WHERE id > ? AND time_millis < ? AND type = 'block'"
            + " AND action IN ('placed','broke') AND rolled_back = 0 AND (data IS NULL OR data NOT LIKE 'frame:%')"
            + " ORDER BY id LIMIT " + BATCH;

    private record Key(String player, String uuid, String action, String block, String world, long day, int cx, int cz) {}

    private static final class Group {
        int count;
        long maxTime;
        int y;
        final List<Long> ids = new ArrayList<>();
    }

    private record Result(long lastId, int scanned, int removed, int created) {}

    private final DatabaseManager db;
    private final AtomicBoolean running = new AtomicBoolean();

    public Compactor(DatabaseManager db, Logger log) {
        this.db = db;
    }

    public boolean isRunning() { return running.get(); }

    public static CoreProtectImporter.Reporter logReporter(Logger log) {
        return new CoreProtectImporter.Reporter() {
            @Override public void info(String m) { log.info("[Compaction] " + m); }
            @Override public void ok(String m) { log.info("[Compaction] " + m.replaceAll("<[^>]+>", "")); }
            @Override public void err(String m) { log.warning("[Compaction] " + m); }
        };
    }

    public void start(CoreProtectImporter.Reporter rep, long olderThanMs, boolean dry) {
        if (db.isMigrating()) { rep.err("A database migration is running. Try again afterwards."); return; }
        if (!running.compareAndSet(false, true)) { rep.err("A compaction is already running."); return; }
        Thread t = new Thread(() -> {
            try {
                run(rep, olderThanMs, dry);
            } catch (Throwable e) {
                rep.err("Compaction failed: " + e.getMessage() + " (see console)");
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        }, "UniversalProtect-Compaction");
        t.setDaemon(true);
        t.start();
    }

    private void run(CoreProtectImporter.Reporter rep, long olderThanMs, boolean dry) throws Exception {
        long cutoff = System.currentTimeMillis() - olderThanMs;
        rep.info((dry ? "Dry run: " : "") + "compacting block rows older than " + Msg.ago(olderThanMs) + " ...");
        db.flushNow();
        long last = 0, scanned = 0, removed = 0, created = 0;
        int batches = 0;
        while (true) {
            final long from = last;
            Result r = db.call(() -> batch(from, cutoff, dry));
            if (r.scanned() == 0) break;
            last = r.lastId();
            scanned += r.scanned();
            removed += r.removed();
            created += r.created();
            if (++batches % 10 == 0) rep.info("Scanned " + scanned + " rows so far ...");
        }
        if (dry) {
            rep.ok("Dry run finished: <white>" + removed + "</white> of " + scanned + " scanned rows would be replaced by <white>"
                    + created + "</white> summary rows. Nothing was changed.");
        } else {
            rep.ok("Compaction finished: <white>" + removed + "</white> rows replaced by <white>" + created
                    + "</white> summary rows. Run /up purge #optimize to free the disk space.");
        }
    }

    private Result batch(long from, long cutoff, boolean dry) throws SQLException {
        Connection con = db.connection();
        DbType type = db.type();
        List<LogRow> rows = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECT)) {
            ps.setLong(1, from);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(DatabaseManager.map(rs));
            }
        }
        if (rows.isEmpty()) return new Result(from, 0, 0, 0);
        long lastId = rows.get(rows.size() - 1).id();

        Map<Key, Group> groups = new LinkedHashMap<>();
        for (LogRow r : rows) {
            Key k = new Key(r.player(), r.uuid(), r.action(), Actions.blockId(r.data()), r.world(),
                    Math.floorDiv(r.time(), DAY), Math.floorDiv(r.x(), 16), Math.floorDiv(r.z(), 16));
            Group g = groups.computeIfAbsent(k, x -> new Group());
            if (g.ids.isEmpty()) g.y = r.y();
            g.count++;
            g.maxTime = Math.max(g.maxTime, r.time());
            g.ids.add(r.id());
        }

        List<LogRow> summaries = new ArrayList<>();
        List<Long> remove = new ArrayList<>();
        for (Map.Entry<Key, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            if (g.count < MIN_GROUP) continue;
            Key k = e.getKey();
            remove.addAll(g.ids);
            summaries.add(new LogRow(0, g.maxTime, "block", k.player(), k.uuid(),
                    Actions.BROKE.equals(k.action()) ? Actions.AGG_BROKE : Actions.AGG_PLACED, k.block(), k.world(),
                    k.cx() * 16, g.y, k.cz() * 16, null, null, g.count, "agg", false));
        }
        if (dry || remove.isEmpty()) return new Result(lastId, rows.size(), remove.size(), summaries.size());

        long base = db.nextId().getAndAdd(summaries.size());
        List<LogRow> out = new ArrayList<>(summaries.size());
        for (int i = 0; i < summaries.size(); i++) {
            LogRow s = summaries.get(i);
            out.add(new LogRow(base + i, s.time(), s.type(), s.player(), s.uuid(), s.action(), s.data(), s.world(),
                    s.x(), s.y(), s.z(), s.oldData(), s.oldExtra(), s.amount(), s.meta(), s.rolledBack()));
        }
        db.withTx(con, type, () -> {
            DatabaseManager.insertRows(con, out);
            for (int i = 0; i < remove.size(); i += DELETE_CHUNK) {
                StringBuilder sb = new StringBuilder();
                for (Long id : remove.subList(i, Math.min(remove.size(), i + DELETE_CHUNK))) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(id.longValue());
                }
                try (Statement st = con.createStatement()) { st.execute(type.deleteSql("id IN (" + sb + ")")); }
            }
        });
        return new Result(lastId, rows.size(), remove.size(), summaries.size());
    }
}
