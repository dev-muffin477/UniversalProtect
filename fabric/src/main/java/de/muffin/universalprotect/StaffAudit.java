package de.muffin.universalprotect;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

public final class StaffAudit {
    public static final String TYPE = "audit";
    private static final int MAX_TEXT = 240;

    private final DatabaseManager db;
    private final Logger log;

    public StaffAudit(DatabaseManager db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void record(String name, String uuid, String world, int x, int y, int z, String what, String details) {
        String text = (what + (details == null || details.isBlank() ? "" : " " + details.trim()));
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        LogEntry e = new LogEntry(System.currentTimeMillis(), TYPE, name == null ? "Console" : name, uuid, Actions.STAFF, text,
                world == null ? "" : world, x, y, z, null, null, 1, null);
        db.submit(() -> {
            try {
                DatabaseManager.insertRows(db.connection(), List.of(LogRow.fromEntry(db.nextId().getAndIncrement(), e)));
            } catch (SQLException ex) {
                log.warning("[Audit] Could not write audit entry: " + ex.getMessage());
            }
        });
    }

    public void view(Investigator.Out out, Map<String, String> params) {
        db.submit(() -> {
            try {
                db.flush();
                Query q = new Query();
                q.type = TYPE;
                long span = params.containsKey("t") ? ParamUtils.parseTime(params.get("t")) : 7L * 86_400_000L;
                if (span > 0) q.since = System.currentTimeMillis() - span;
                q.users = ParamUtils.split(params.get("u"));
                long total = db.count(q);
                if (total == 0) { out.err("No staff actions in this period."); return; }
                List<LogRow> rows = db.selectRows(q, false, 300, 0, null, null);
                long now = System.currentTimeMillis();
                List<String> L = new ArrayList<>();
                for (LogRow r : rows) {
                    String world = r.world() == null ? "" : r.world().replace("'", "");
                    StringBuilder sb = new StringBuilder(" <hover:show_text:'").append(Msg.date(r.time())).append("'><green>")
                            .append(Msg.ago(now - r.time())).append("</green></hover> <dark_gray>│</dark_gray> <white>")
                            .append(Msg.esc(r.player())).append("</white> <gold>").append(Msg.esc(r.data())).append("</gold>");
                    if (!world.isEmpty() && (r.x() != 0 || r.y() != 0 || r.z() != 0)) {
                        sb.append(" <dark_gray>[<click:run_command:'/up tp ").append(world).append(' ').append(r.x()).append(' ').append(r.y()).append(' ').append(r.z())
                                .append("'><gray>").append(r.x()).append(' ').append(r.y()).append(' ').append(r.z()).append("</gray></click>]</dark_gray>");
                    }
                    L.add(sb.toString());
                }
                int page = 1;
                try { if (params.containsKey("p")) page = Math.max(1, Integer.parseInt(params.get("p").trim())); }
                catch (NumberFormatException ignored) {  }
                List<String> pinned = List.of(" <gray><white>" + total + "</white> staff actions" + (total > 300 ? " <dark_gray>(newest 300 shown)</dark_gray>" : "") + "</gray>");
                Pager.send(out, "Staff Audit", pinned, L, page, null);
            } catch (Exception e) {
                log.warning("[Audit] View failed: " + e.getMessage());
                out.err("Audit view failed, see console.");
            }
        });
    }
}
