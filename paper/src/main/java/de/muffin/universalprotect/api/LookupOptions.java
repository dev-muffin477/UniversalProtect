package de.muffin.universalprotect.api;

import de.muffin.universalprotect.Actions;
import de.muffin.universalprotect.ParamUtils;
import de.muffin.universalprotect.Query;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.util.*;

public final class LookupOptions {
    public enum BlockAction {
        BREAK(0, Actions.BROKE), PLACE(1, Actions.PLACED), INTERACTION(2, Actions.USE);
        final int id; final String db;
        BlockAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    public enum EntityAction {
        KILL(3, Actions.KILL), SPAWN(13, Actions.SPAWN);
        final int id; final String db;
        EntityAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    public enum ContainerAction {
        REMOVE(0, Actions.TAKE), ADD(1, Actions.PUT);
        final int id; final String db;
        ContainerAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    public enum SessionAction {
        LOGOUT(0, Actions.QUIT), LOGIN(1, Actions.JOIN);
        final int id; final String db;
        SessionAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    public enum ItemAction {
        DROP(2, Actions.DROP), PICKUP(3, Actions.PICKUP), REMOVE_ENDER(4, Actions.ENDER_REMOVE), ADD_ENDER(5, Actions.ENDER_ADD),
        THROW(6, Actions.THROW), SHOOT(7, Actions.SHOOT), BREAK(8, Actions.TOOL_BREAK), DESTROY(9, Actions.DESTROY),
        CREATE(10, Actions.CREATE), SELL(11, Actions.SELL), BUY(12, Actions.BUY);
        final int id; final String db;
        ItemAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    public enum InventoryAction {
        REMOVE(0, Actions.INV_REMOVE), ADD(1, Actions.INV_ADD);
        final int id; final String db;
        InventoryAction(int id, String db) { this.id = id; this.db = db; }
        public int getId() { return id; }
    }

    private final List<String> users, excludeUsers;
    private final int timeSeconds;
    private final Location location;
    private final int radius;
    private final World world;
    private final int limitOffset, limitCount;
    private final List<Material> includeMaterials, excludeMaterials;
    private final List<EntityType> includeEntities, excludeEntities;
    private final Set<String> actions;
    private final List<String> startsWithAny, startsWithNone;

    private LookupOptions(Builder b) {
        users = List.copyOf(b.users); excludeUsers = List.copyOf(b.excludeUsers);
        timeSeconds = b.time; location = b.location; radius = b.radius; world = b.world;
        limitOffset = b.limitOffset; limitCount = b.limitCount;
        includeMaterials = List.copyOf(b.includeMaterials); excludeMaterials = List.copyOf(b.excludeMaterials);
        includeEntities = List.copyOf(b.includeEntities); excludeEntities = List.copyOf(b.excludeEntities);
        actions = Collections.unmodifiableSet(new LinkedHashSet<>(b.actions));
        startsWithAny = List.copyOf(b.startsWithAny); startsWithNone = List.copyOf(b.startsWithNone);
    }

    public static Builder builder() { return new Builder(); }

    public int getTime() { return timeSeconds; }
    public Location getLocation() { return location; }
    public int getRadius() { return radius; }
    public List<String> getUsers() { return users; }

    public Query toQuery(Set<String> defaults) {
        Query q = new Query();
        q.users = new ArrayList<>(users);
        q.excludeUsers = new ArrayList<>(excludeUsers);
        if (timeSeconds > 0) q.since = System.currentTimeMillis() - timeSeconds * 1000L;
        List<String> worlds = new ArrayList<>();
        if (world != null) worlds.add(world.getName());
        if (location != null) {
            if (location.getWorld() != null && worlds.isEmpty()) worlds.add(location.getWorld().getName());
            q.x = location.getBlockX(); q.y = location.getBlockY(); q.z = location.getBlockZ();
            q.radius = Math.max(0, radius);
        }
        q.worlds = worlds;
        q.actions = actions.isEmpty() ? new LinkedHashSet<>(defaults) : new LinkedHashSet<>(actions);
        q.rawAction = String.join(",", q.actions);
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        for (Material m : includeMaterials) inc.add(m.name().toLowerCase(Locale.ROOT));
        for (EntityType e : includeEntities) inc.add(e.name().toLowerCase(Locale.ROOT));
        for (Material m : excludeMaterials) exc.add(m.name().toLowerCase(Locale.ROOT));
        for (EntityType e : excludeEntities) exc.add(e.name().toLowerCase(Locale.ROOT));
        q.include = inc;
        q.exclude = exc;
        q.filterInclude = new ArrayList<>(startsWithAny);
        q.filterExclude = new ArrayList<>(startsWithNone);
        return q;
    }

    public int limitOffset() { return limitOffset; }
    public int limitCount() { return limitCount; }

    public static List<String> users(String csv) { return ParamUtils.split(csv); }

    public static final class Builder {
        private final List<String> users = new ArrayList<>(), excludeUsers = new ArrayList<>();
        private int time;
        private Location location;
        private int radius = -1;
        private World world;
        private int limitOffset, limitCount;
        private final List<Material> includeMaterials = new ArrayList<>(), excludeMaterials = new ArrayList<>();
        private final List<EntityType> includeEntities = new ArrayList<>(), excludeEntities = new ArrayList<>();
        private final Set<String> actions = new LinkedHashSet<>();
        private final List<String> startsWithAny = new ArrayList<>(), startsWithNone = new ArrayList<>();

        private Builder() {}

        public Builder user(String user) { if (user != null) users.add(user); return this; }
        public Builder users(List<String> list) { if (list != null) users.addAll(list); return this; }
        public Builder excludeUsers(List<String> list) { if (list != null) excludeUsers.addAll(list); return this; }
        public Builder time(int seconds) { this.time = seconds; return this; }
        public Builder location(Location l) { this.location = l; if (radius < 0) radius = 0; return this; }
        public Builder radius(Location l, int radius) { this.location = l; this.radius = Math.max(0, radius); return this; }
        public Builder world(World w) { this.world = w; return this; }
        public Builder limit(int offset, int count) { this.limitOffset = Math.max(0, offset); this.limitCount = Math.max(0, count); return this; }
        public Builder includeMaterials(List<Material> l) { if (l != null) includeMaterials.addAll(l); return this; }
        public Builder excludeMaterials(List<Material> l) { if (l != null) excludeMaterials.addAll(l); return this; }
        public Builder includeEntities(List<EntityType> l) { if (l != null) includeEntities.addAll(l); return this; }
        public Builder excludeEntities(List<EntityType> l) { if (l != null) excludeEntities.addAll(l); return this; }
        public Builder blockActions(List<BlockAction> l) { if (l != null) for (BlockAction a : l) actions.add(a.db); return this; }
        public Builder entityActions(List<EntityAction> l) { if (l != null) for (EntityAction a : l) actions.add(a.db); return this; }
        public Builder containerActions(List<ContainerAction> l) { if (l != null) for (ContainerAction a : l) actions.add(a.db); return this; }
        public Builder itemActions(List<ItemAction> l) { if (l != null) for (ItemAction a : l) actions.add(a.db); return this; }
        public Builder inventoryActions(List<InventoryAction> l) { if (l != null) for (InventoryAction a : l) actions.add(a.db); return this; }
        public Builder sessionActions(List<SessionAction> l) { if (l != null) for (SessionAction a : l) actions.add(a.db); return this; }
        public Builder textStartsWithAny(List<String> l) { if (l != null) startsWithAny.addAll(l); return this; }
        public Builder textStartsWithNone(List<String> l) { if (l != null) startsWithNone.addAll(l); return this; }

        public LookupOptions build() { return new LookupOptions(this); }
    }
}
