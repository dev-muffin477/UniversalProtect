package de.muffin.universalprotect;

public record LogRow(long id, long time, String type, String player, String uuid, String action, String data,
                     String world, int x, int y, int z, String oldData, String oldExtra, int amount, String meta,
                     boolean rolledBack) {

    public static LogRow fromEntry(long id, LogEntry e) {
        return new LogRow(id, e.time(), e.type(), e.player(), e.uuid(), e.action(), e.data(), e.world(),
                e.x(), e.y(), e.z(), e.oldData(), e.oldExtra(), e.amount(), e.meta(), false);
    }
}
