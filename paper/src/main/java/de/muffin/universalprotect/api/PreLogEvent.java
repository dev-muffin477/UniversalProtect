package de.muffin.universalprotect.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PreLogEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Action {
        UNKNOWN, BLOCK_BREAK, BLOCK_PLACE, PLAYER_INTERACTION, ENTITY_KILL, PLAYER_KILL, ENTITY_SPAWN,
        ENTITY_INTERACTION, SIGN_TEXT, CONTAINER_TRANSACTION, ITEM_TRANSACTION, PLAYER_COMMAND
    }

    private String user;
    private Location location;
    private final Action action;
    private final int actionId;
    private final Material material;
    private final EntityType entityType;
    private final String message;
    private boolean cancelled;

    public PreLogEvent(String user, Location location, Action action, int actionId, Material material, EntityType entityType, String message) {
        super(true);
        this.user = user;
        this.location = location;
        this.action = action;
        this.actionId = actionId;
        this.material = material;
        this.entityType = entityType;
        this.message = message;
    }

    public PreLogEvent(String user, Location location) {
        this(user, location, Action.UNKNOWN, -1, null, null, null);
    }

    public String getUser() { return user; }

    public void setUser(String user) {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("user must not be null or empty");
        this.user = user;
    }

    public Location getLocation() { return location; }

    public void setLocation(Location location) {
        if (location == null) throw new IllegalArgumentException("location must not be null");
        this.location = location;
    }

    public Action getAction() { return action; }
    public int getActionId() { return actionId; }
    public Material getMaterial() { return material; }
    public EntityType getEntityType() { return entityType; }
    public String getMessage() { return message; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
