package de.muffin.universalprotect;

public record LogEntry(long time, String type, String player, String uuid, String action, String data,
                       String world, int x, int y, int z, String oldData, String oldExtra, int amount, String meta) {

    public static LogEntry of(String type, String player, String uuid, String action, String data,
                              String world, int x, int y, int z) {
        return new LogEntry(System.currentTimeMillis(), type, player, uuid, action, data, world, x, y, z, null, null, 1, null);
    }

    public LogEntry withOld(String oldData, String oldExtra) {
        return new LogEntry(time, type, player, uuid, action, data, world, x, y, z, oldData, oldExtra, amount, meta);
    }

    public LogEntry withAmount(int amount, String meta) {
        return new LogEntry(time, type, player, uuid, action, data, world, x, y, z, oldData, oldExtra, amount, meta);
    }

    public LogEntry withUser(String player) {
        return new LogEntry(time, type, player, uuid, action, data, world, x, y, z, oldData, oldExtra, amount, meta);
    }

    public LogEntry withLocation(String world, int x, int y, int z) {
        return new LogEntry(time, type, player, uuid, action, data, world, x, y, z, oldData, oldExtra, amount, meta);
    }
}
