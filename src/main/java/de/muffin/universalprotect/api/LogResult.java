package de.muffin.universalprotect.api;

import de.muffin.universalprotect.Actions;
import de.muffin.universalprotect.LogRow;

import java.util.Locale;

/** Basisklasse aller typisierten Lookup-Ergebnisse (entspricht CoreProtects CoreProtectResult). */
public class LogResult {
    protected final LogRow row;

    protected LogResult(LogRow row) { this.row = row; }

    public String getPlayer() { return row.player(); }
    /** Zeitstempel in Millisekunden. */
    public long getTimestamp() { return row.time(); }
    public int getX() { return row.x(); }
    public int getY() { return row.y(); }
    public int getZ() { return row.z(); }
    public String worldName() { return row.world(); }
    public int getActionId() { return Actions.cpId(row.action()); }
    public boolean isRolledBack() { return row.rolledBack(); }
    public String getUuid() { return row.uuid(); }
    public LogRow getRow() { return row; }

    public String getActionString() {
        return switch (getActionId()) {
            case 0 -> "break";
            case 1 -> "place";
            case 2 -> "click";
            case 3 -> "kill";
            case 4 -> Actions.PUT.equals(row.action()) ? "add" : "remove";
            case 6 -> "chat";
            case 7 -> "command";
            case 8 -> Actions.JOIN.equals(row.action()) ? "login" : "logout";
            case 9 -> "username";
            case 10 -> "sign";
            case 11 -> row.action();
            case 13 -> "spawn";
            default -> row.action();
        };
    }

    /** Item-/Block-ID, z.B. "minecraft:stone" (oder null). */
    static String material(String data) {
        if (data == null) return null;
        String first = data.split("[\\[ ]")[0];
        return first.contains(":") ? first : "minecraft:" + first;
    }

    static int amountOf(LogRow r) { return Math.max(1, r.amount()); }

    public static class BlockResult extends LogResult {
        public BlockResult(LogRow row) { super(row); }
        public String getType() { return material(row.data()); }
        /** Block-State-String, z.B. "minecraft:oak_log[axis=y]". */
        public String getBlockData() { return row.data(); }
    }

    public static class EntityResult extends LogResult {
        public EntityResult(LogRow row) { super(row); }
        /** Entity-Typ, z.B. "zombie". */
        public String getEntityType() {
            return row.data() == null ? null : row.data().split(":")[0].toLowerCase(Locale.ROOT);
        }
    }

    /** Container-, Item- und Inventar-Transaktionen. */
    public static class ItemResult extends LogResult {
        public ItemResult(LogRow row) { super(row); }
        public String getType() { return material(row.data()); }
        public int getAmount() { return amountOf(row); }
    }

    public static class ContainerResult extends ItemResult {
        public ContainerResult(LogRow row) { super(row); }
        public boolean isAdded() { return Actions.PUT.equals(row.action()); }
    }

    public static class InventoryResult extends ItemResult {
        public InventoryResult(LogRow row) { super(row); }
        public boolean isAdded() { return Actions.INV_ADD.equals(row.action()); }
    }

    public static class MessageResult extends LogResult {
        public MessageResult(LogRow row) { super(row); }
        public String getMessage() { return row.data(); }
    }

    public static class SignResult extends MessageResult {
        public SignResult(LogRow row) { super(row); }
    }

    public static class SessionResult extends LogResult {
        public SessionResult(LogRow row) { super(row); }
        public boolean isLogin() { return Actions.JOIN.equals(row.action()); }
    }

    public static class UsernameResult extends LogResult {
        public UsernameResult(LogRow row) { super(row); }
        /** "alt -> neu" */
        public String getChange() { return row.data(); }
        public String getOldName() { return row.data() == null ? null : row.data().split(" -> ")[0]; }
        public String getNewName() {
            if (row.data() == null) return null;
            String[] p = row.data().split(" -> ");
            return p.length > 1 ? p[1] : p[0];
        }
    }
}
