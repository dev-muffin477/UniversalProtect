package de.muffin.universalprotect;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class LookupView {
    private LookupView() {}

    private static String esc(String s) { return Msg.esc(s); }

    public static String describe(LogRow r) {
        String a = r.action() == null ? "" : r.action();
        String d = r.data() == null ? "" : r.data();
        String meta = r.meta() != null && !r.meta().isEmpty() && r.meta().length() < 24 && !r.meta().contains("-")
                ? " <gray>(" + esc(r.meta()) + ")</gray>" : "";
        String text = switch (a) {
            case Actions.BROKE -> "<red>broke</red> <white>" + esc(blockLabel(d)) + "</white>";
            case Actions.PLACED -> "<green>placed</green> <white>" + esc(blockLabel(d)) + "</white>";
            case Actions.PUT -> "<green>put</green> <white>" + esc(d) + "</white> <gray>into container</gray>";
            case Actions.TAKE -> "<red>took</red> <white>" + esc(d) + "</white> <gray>from container</gray>";
            case Actions.INV_ADD -> "<green>received</green> <white>" + esc(d) + "</white>" + meta;
            case Actions.INV_REMOVE -> "<red>lost</red> <white>" + esc(d) + "</white>" + meta;
            case Actions.DROP -> "<gold>dropped</gold> <white>" + esc(d) + "</white>";
            case Actions.PICKUP -> "<yellow>picked up</yellow> <white>" + esc(d) + "</white>";
            case Actions.THROW -> "<gold>threw</gold> <white>" + esc(d) + "</white>";
            case Actions.SHOOT -> "<gold>shot</gold> <white>" + esc(d) + "</white>";
            case Actions.TOOL_BREAK -> "<red>broke tool</red> <white>" + esc(d) + "</white>";
            case Actions.DESTROY -> "<red>lost (destroyed)</red> <white>" + esc(d) + "</white>";
            case Actions.CREATE -> "<green>created</green> <white>" + esc(d) + "</white>";
            case Actions.SELL -> "<gold>sold</gold> <white>" + esc(d) + "</white>";
            case Actions.BUY -> "<green>bought</green> <white>" + esc(d) + "</white>";
            case Actions.ENDER_ADD -> "<green>put</green> <white>" + esc(d) + "</white> <gray>into ender chest</gray>";
            case Actions.ENDER_REMOVE -> "<red>took</red> <white>" + esc(d) + "</white> <gray>from ender chest</gray>";
            case Actions.SIGN -> "<yellow>wrote sign</yellow> <white>" + esc(d) + "</white>";
            case Actions.USE -> "<gray>clicked</gray> <white>" + esc(d) + "</white>";
            case Actions.FRAME -> "<gold>frame:</gold> <white>" + esc(d) + "</white>";
            case Actions.JOIN -> "<green>logged in</green>";
            case Actions.QUIT -> "<red>logged out</red>";
            case Actions.CHAT -> "<yellow>said</yellow> <white>" + esc(d) + "</white>";
            case Actions.COMMAND -> "<gold>ran</gold> <white>" + esc(d) + "</white>";
            case Actions.KILL -> "<red>killed</red> <white>" + esc(d.toLowerCase(Locale.ROOT)) + "</white>";
            case Actions.SPAWN -> "<green>spawned</green> <white>" + esc(d.toLowerCase(Locale.ROOT)) + "</white>";
            case Actions.DEATH -> "<dark_red>died</dark_red> <white>" + esc(d) + "</white>";
            case Actions.TELEPORT -> "<yellow>teleported</yellow> <white>" + esc(d.replace("->", "→")) + "</white>";
            case Actions.STAFF -> "<gold>staff:</gold> <white>" + esc(d) + "</white>";
            case Actions.USERNAME -> "<gray>changed name:</gray> <white>" + esc(d.replace("->", "→")) + "</white>";
            case Actions.AGG_BROKE -> "<red>broke</red> <white>" + r.amount() + "x " + esc(blockLabel(d)) + "</white> <gray>(daily summary)</gray>";
            case Actions.AGG_PLACED -> "<green>placed</green> <white>" + r.amount() + "x " + esc(blockLabel(d)) + "</white> <gray>(daily summary)</gray>";
            default -> "<gray>" + esc(a) + "</gray> <white>" + esc(d) + "</white>";
        };
        if (r.meta() != null && r.meta().startsWith("cmd:")) text += " <gray>(/" + esc(r.meta().substring(4)) + ")</gray>";
        if (r.rolledBack()) return "<dark_gray><strikethrough>" + stripTags(text) + "</strikethrough></dark_gray>";
        return text;
    }

    private static String stripTags(String mini) {
        return mini.replaceAll("</?(?:green|red|gold|yellow|white|gray|dark_red|dark_gray)>", "");
    }

    private static String blockLabel(String data) {
        if (data != null && FrameState.isFrame(data)) return FrameState.describe(data);
        return Msg.cleanBlock(data);
    }

    public static void none(CommandSender to) { Msg.err(to, "No records found."); }

    public static void count(CommandSender to, long total) { Msg.send(to, "<white>" + total + "</white> <gray>records found.</gray>"); }

    private static void emit(CommandSender to, String s) {
        to.sendMessage(Msg.MM.deserialize(s));
    }

    public static void summary(CommandSender to, List<String[]> rows) {
        if (rows.isEmpty()) { none(to); return; }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String[] r : rows) {
            lines.add(" <green>" + esc(r[0]) + "</green> <gray>" + esc(r[1]) + "</gray> <dark_gray>»</dark_gray> <white>" + r[2] + "</white>");
        }
        String head = Pager.head("Summary");
        int budget = Pager.MAX_LINES - Pager.height(head) - 1;
        int shown = Pager.fit(lines, 0, budget);
        if (shown < lines.size()) shown = Pager.fit(lines, 0, budget - 1);
        emit(to, head);
        for (int i = 0; i < shown; i++) emit(to, lines.get(i));
        if (shown < lines.size()) emit(to, " <dark_gray>… " + (lines.size() - shown) + " more rows. Narrow it down with u:, a:, t: or r:</dark_gray>");
        emit(to, Pager.foot());
    }

    public static int page(CommandSender to, Query q, List<LogRow> rows, int page, long start, long total, DatabaseManager db) {
        boolean silent = q.hasTag("silent");
        boolean verbose = q.hasTag("verbose");
        long now = System.currentTimeMillis();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (LogRow r : rows) lines.add(line(r, now, verbose, silent, db));
        String head = Pager.head("Lookup");
        int budget = silent ? Pager.MAX_LINES - 1 : Pager.MAX_LINES - Pager.height(head) - 2;
        int shown = Math.min(lines.size(), Math.max(1, Pager.fit(lines, 0, budget)));
        if (lines.isEmpty()) shown = 0;
        long end = start + shown;
        if (!silent) emit(to, head);
        for (int i = 0; i < shown; i++) emit(to, lines.get(i));
        if (silent) {
            if (end < total) emit(to, " <dark_gray>… " + (total - end) + " more. /up page</dark_gray>");
            return shown;
        }
        StringBuilder nav = new StringBuilder(" ");
        if (page > 1) nav.append("<click:run_command:'/up page ").append(page - 1).append("'><hover:show_text:'Previous page'><green>« Prev</green></hover></click>");
        else nav.append("<dark_gray>« Prev</dark_gray>");
        nav.append(" <dark_gray>|</dark_gray> <white>").append(start + 1).append("–").append(end).append("</white> <gray>of</gray> <white>").append(total).append("</white> <dark_gray>|</dark_gray> ");
        if (end < total) nav.append("<click:run_command:'/up page ").append(page + 1).append("'><hover:show_text:'Next page'><green>Next »</green></hover></click>");
        else nav.append("<dark_gray>Next »</dark_gray>");
        emit(to, nav.toString());
        emit(to, Pager.foot());
        return shown;
    }

    private static String line(LogRow r, long now, boolean verbose, boolean silent, DatabaseManager db) {
        String world = r.world() == null ? "" : r.world().replace("'", "");
        String time = "<hover:show_text:'" + Msg.date(r.time()) + "'><green>" + Msg.ago(now - r.time()) + "</green></hover>";
        String who = r.player() == null ? "?" : r.player();
        String whoHover = "";
        if (r.uuid() != null) {
            String cur = db.currentName(r.uuid());
            whoHover = "UUID: " + r.uuid() + (cur != null && !cur.equalsIgnoreCase(who) ? " (now: " + cur + ")" : "");
        }
        String user = whoHover.isEmpty() ? "<white>" + esc(who) + "</white>"
                : "<hover:show_text:'" + esc(whoHover).replace("'", "") + "'><white>" + esc(who) + "</white></hover>";

        StringBuilder sb = new StringBuilder(" ").append(time).append(" <dark_gray>│</dark_gray> ").append(user).append(" ").append(describe(r));
        if (!silent) {
            sb.append(" <dark_gray>[<hover:show_text:'Teleport • ").append(esc(world)).append("'><click:run_command:'/up tp ")
                    .append(world).append(' ').append(r.x()).append(' ').append(r.y()).append(' ').append(r.z()).append("'><gray>")
                    .append(r.x()).append(' ').append(r.y()).append(' ').append(r.z()).append("</gray></click></hover>]</dark_gray>");
        }
        if (verbose) sb.append(" <dark_gray>#").append(r.id()).append(' ').append(esc(world)).append("</dark_gray>");
        return sb.toString();
    }
}
