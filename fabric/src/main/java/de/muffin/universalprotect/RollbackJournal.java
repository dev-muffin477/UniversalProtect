package de.muffin.universalprotect;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class RollbackJournal {
    public record Entry(long id, long started, String actor, boolean restore, Query query, int done, int total) {}

    private static final String SEP = "\u0001";

    private final DatabaseManager db;
    private final Logger log;
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean ready;

    public RollbackJournal(DatabaseManager db, Logger log) {
        this.db = db;
        this.log = log;
    }

    private boolean supported() { return db.type() != DbType.CLICKHOUSE; }

    private boolean ensure() {
        if (ready) return true;
        if (!supported()) return false;
        try (Statement st = db.connection().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS up_journal (id BIGINT, started BIGINT, actor VARCHAR(64), restore_flag INT, query_text TEXT, done_count INT, total INT)");
            ready = true;
        } catch (SQLException e) {
            log.warning("[Recovery] Could not create journal table: " + e.getMessage());
        }
        return ready;
    }

    public long begin(String actor, boolean restore, Query q, int total) {
        if (!supported()) return -1L;
        long started = System.currentTimeMillis();
        long id = started * 16 + (seq.getAndIncrement() & 15);
        String text = encode(q);
        String who = actor == null ? "?" : (actor.length() > 60 ? actor.substring(0, 60) : actor);
        db.submit(() -> {
            if (!ensure()) return;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO up_journal (id, started, actor, restore_flag, query_text, done_count, total) VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, id); ps.setLong(2, started); ps.setString(3, who); ps.setInt(4, restore ? 1 : 0);
                ps.setString(5, text); ps.setInt(6, 0); ps.setInt(7, total);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("[Recovery] Could not write journal: " + e.getMessage());
            }
        });
        return id;
    }

    public void progress(long id, int done) {
        if (id < 0) return;
        db.submit(() -> {
            if (!ready) return;
            try (PreparedStatement ps = db.connection().prepareStatement("UPDATE up_journal SET done_count = ? WHERE id = ?")) {
                ps.setInt(1, done); ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException ignored) {  }
        });
    }

    public void finish(long id) {
        if (id < 0) return;
        db.submit(() -> {
            if (!ready) return;
            try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM up_journal WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("[Recovery] Could not close journal entry: " + e.getMessage());
            }
        });
    }

    public List<Entry> pending() throws Exception {
        if (!supported()) return List.of();
        return db.call(() -> {
            List<Entry> out = new ArrayList<>();
            if (!ensure()) return out;
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, started, actor, restore_flag, query_text, done_count, total FROM up_journal ORDER BY started")) {
                while (rs.next()) {
                    try {
                        out.add(new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4) != 0,
                                decode(rs.getString(5)), rs.getInt(6), rs.getInt(7)));
                    } catch (RuntimeException bad) {
                        log.warning("[Recovery] Ignoring unreadable journal entry " + rs.getLong(1));
                    }
                }
            }
            return out;
        });
    }

    private static void put(StringBuilder sb, String key, Object value) {
        if (value == null) return;
        sb.append(key).append('=').append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)).append('\n');
    }

    private static String join(Collection<String> c) { return c == null ? null : String.join(SEP, c); }

    static String encode(Query q) {
        StringBuilder sb = new StringBuilder();
        put(sb, "type", q.type);
        put(sb, "users", join(q.users));
        put(sb, "xusers", join(q.excludeUsers));
        put(sb, "actions", join(q.actions));
        put(sb, "raw", q.rawAction);
        put(sb, "since", q.since);
        put(sb, "until", q.until);
        put(sb, "radius", q.radius);
        put(sb, "worlds", join(q.worlds));
        put(sb, "x", q.x);
        put(sb, "y", q.y);
        put(sb, "z", q.z);
        put(sb, "page", q.page);
        put(sb, "lines", q.lines);
        put(sb, "include", join(q.include));
        put(sb, "exclude", join(q.exclude));
        put(sb, "finc", join(q.filterInclude));
        put(sb, "fexc", join(q.filterExclude));
        put(sb, "tags", join(q.tags));
        return sb.toString();
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isEmpty()) return out;
        for (String s : v.split(SEP)) if (!s.isEmpty()) out.add(s);
        return out;
    }

    static Query decode(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            m.put(line.substring(0, i), URLDecoder.decode(line.substring(i + 1), StandardCharsets.UTF_8));
        }
        Query q = new Query();
        q.type = m.get("type");
        q.users = list(m.get("users"));
        q.excludeUsers = list(m.get("xusers"));
        q.actions = m.containsKey("actions") ? new LinkedHashSet<>(list(m.get("actions"))) : null;
        q.rawAction = m.get("raw");
        q.since = Long.parseLong(m.getOrDefault("since", "0"));
        q.until = Long.parseLong(m.getOrDefault("until", "0"));
        q.radius = Integer.parseInt(m.getOrDefault("radius", "-1"));
        q.worlds = list(m.get("worlds"));
        q.x = Integer.parseInt(m.getOrDefault("x", "0"));
        q.y = Integer.parseInt(m.getOrDefault("y", "0"));
        q.z = Integer.parseInt(m.getOrDefault("z", "0"));
        q.page = Integer.parseInt(m.getOrDefault("page", "1"));
        q.lines = Integer.parseInt(m.getOrDefault("lines", "15"));
        q.include = list(m.get("include"));
        q.exclude = list(m.get("exclude"));
        q.filterInclude = list(m.get("finc"));
        q.filterExclude = list(m.get("fexc"));
        q.tags = new LinkedHashSet<>(list(m.get("tags")));
        return q;
    }
}
