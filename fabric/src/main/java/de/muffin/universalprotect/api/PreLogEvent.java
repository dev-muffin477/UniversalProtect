package de.muffin.universalprotect.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class PreLogEvent {
    public static final List<Consumer<PreLogEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    public static void register(Consumer<PreLogEvent> listener) { LISTENERS.add(listener); }

    public static void fire(PreLogEvent e) { for (Consumer<PreLogEvent> l : LISTENERS) l.accept(e); }

    public enum Action {
        UNKNOWN, BLOCK_BREAK, BLOCK_PLACE, PLAYER_INTERACTION, ENTITY_KILL, PLAYER_KILL, ENTITY_SPAWN,
        ENTITY_INTERACTION, SIGN_TEXT, CONTAINER_TRANSACTION, ITEM_TRANSACTION, PLAYER_COMMAND
    }

    private String user;
    private String world;
    private int x, y, z;
    private final Action action;
    private final int actionId;
    private final String block;
    private final String entity;
    private final String message;
    private boolean cancelled;

    public PreLogEvent(String user, String world, int x, int y, int z, Action action, int actionId, String block, String entity, String message) {
        this.user = user; this.world = world; this.x = x; this.y = y; this.z = z;
        this.action = action; this.actionId = actionId; this.block = block; this.entity = entity; this.message = message;
    }

    public String getUser() { return user; }

    public void setUser(String user) {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("user must not be null or empty");
        this.user = user;
    }

    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public void setLocation(String world, int x, int y, int z) {
        if (world == null) throw new IllegalArgumentException("world must not be null");
        this.world = world; this.x = x; this.y = y; this.z = z;
    }

    public Action getAction() { return action; }
    public int getActionId() { return actionId; }
    public String getBlock() { return block; }
    public String getEntityType() { return entity; }
    public String getMessage() { return message; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
