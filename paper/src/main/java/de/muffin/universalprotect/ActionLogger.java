package de.muffin.universalprotect;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ActionLogger implements Listener {
    private static final String AIR = "minecraft:air";
    private static final List<String> IGNORED_COMMANDS = List.of(
            "/login", "/l ", "/register", "/reg ", "/changepassword", "/unregister", "/up ", "/universalprotect");
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final Set<InventoryType> TRACKED = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL,
            InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER,
            InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING);

    private record Actor(String name, String uuid) {
        static Actor of(Player p) { return new Actor(p.getName(), p.getUniqueId().toString()); }
        static Actor of(String name) { return new Actor(name, null); }
    }

    private record Snapshot(Location loc, boolean ender, List<ItemStack> items) {}
    private record EggUse(Location loc, long time) {}

    private final UniversalProtect plugin;
    private final Map<UUID, Snapshot> openContainers = new HashMap<>();
    private final Map<UUID, EggUse> eggUses = new HashMap<>();
    private final Map<UUID, String> lethalCache = new HashMap<>();
    private final Set<UUID> spawnLogged = Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) { return size() > 256; }
    });
    private final Map<String, Long> alertCooldown = new ConcurrentHashMap<>();

    public ActionLogger(UniversalProtect plugin) { this.plugin = plugin; }

    private DatabaseManager db() { return plugin.db(); }

    private boolean on(String key, World w) { return plugin.settings().opt(key, w == null ? null : w.getName()); }

    private boolean on(String key, Location l) { return on(key, l == null ? null : l.getWorld()); }

    private void emit(String type, Actor who, String action, String data, Location l, String oldData, String oldExtra, int amount, String meta) {
        if (l == null || l.getWorld() == null) return;
        db().log(new LogEntry(System.currentTimeMillis(), type, who.name(), who.uuid(), action, data, l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), oldData, oldExtra, amount, meta));
    }

    private void logAt(String type, Actor who, String action, String data, Location l) {
        emit(type, who, action, data, l, null, null, 1, null);
    }

    private void logBroken(Actor who, Block b) { logBroken(who, b, null); }

    private void logBroken(Actor who, Block b, String meta) {
        BlockData d = b.getBlockData();
        if (d.getMaterial().isAir()) return;
        String s = d.getAsString();
        emit("block", who, Actions.BROKE, s, b.getLocation(), s, BlockExtras.capture(b.getState()), 1, meta);
    }

    private void logPlaced(Actor who, Block b, BlockData oldData, BlockData newData) {
        if (newData.getMaterial().isAir()) return;
        emit("block", who, Actions.PLACED, newData.getAsString(), b.getLocation(), oldData.getAsString(), null, 1, null);
    }

    private void logChange(Actor who, Location l, String oldState, String newState) {
        if (!oldState.equals(AIR)) emit("block", who, Actions.BROKE, oldState, l, oldState, null, 1, null);
        if (!newState.equals(AIR)) emit("block", who, Actions.PLACED, newState, l, oldState, null, 1, null);
    }

    private static String stackText(ItemStack s) { return ItemSerializer.text(s); }

    private void logItem(String type, Actor who, String action, ItemStack stack, int amount, Location l, String meta) {
        if (stack == null || stack.getType().isAir() || amount <= 0) return;
        emit(type, who, action, stack.getType().name().toLowerCase(Locale.ROOT) + " x" + amount, l, null, ItemSerializer.encodeProto(stack), amount, meta);
    }

    private static boolean isDependent(Material m) {
        return Tag.SAPLINGS.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.CROPS.isTagged(m)
                || Tag.STANDING_SIGNS.isTagged(m) || Tag.WOOL_CARPETS.isTagged(m) || Tag.RAILS.isTagged(m)
                || Tag.PRESSURE_PLATES.isTagged(m) || Tag.DOORS.isTagged(m) || Tag.CANDLES.isTagged(m)
                || m == Material.TORCH || m == Material.REDSTONE_TORCH || m == Material.SOUL_TORCH
                || m == Material.REDSTONE_WIRE || m == Material.SNOW || m == Material.SUGAR_CANE
                || m == Material.CACTUS || m == Material.BAMBOO;
    }

    private static boolean isWallAttached(Block n, BlockFace f) {
        Material m = n.getType();
        boolean candidate = Tag.WALL_SIGNS.isTagged(m) || Tag.BUTTONS.isTagged(m)
                || m == Material.WALL_TORCH || m == Material.REDSTONE_WALL_TORCH || m == Material.SOUL_WALL_TORCH
                || m == Material.LADDER || m == Material.TRIPWIRE_HOOK || m == Material.LEVER
                || m.name().endsWith("_WALL_BANNER");
        if (!candidate) return false;
        BlockData nd = n.getBlockData();
        if (nd instanceof FaceAttachable fa && fa.getAttachedFace() != FaceAttachable.AttachedFace.WALL) return false;
        return nd instanceof Directional dir && dir.getFacing() == f;
    }

    private void collect(Block b, Set<Block> out) {
        out.add(b);
        BlockData d = b.getBlockData();
        Block top = b;
        if (d instanceof Bisected bis && !(d instanceof Stairs) && !(d instanceof TrapDoor)) {
            if (bis.getHalf() == Bisected.Half.BOTTOM) {
                top = b.getRelative(BlockFace.UP);
                out.add(top);
            } else {
                out.add(b.getRelative(BlockFace.DOWN));
            }
        } else if (d instanceof Bed bed) {
            out.add(bed.getPart() == Bed.Part.FOOT ? b.getRelative(bed.getFacing()) : b.getRelative(bed.getFacing().getOppositeFace()));
        }
        Block up = top.getRelative(BlockFace.UP);
        int guard = 0;
        while (isDependent(up.getType()) && guard++ < 64) {
            out.add(up);
            up = up.getRelative(BlockFace.UP);
        }
        for (BlockFace f : SIDES) {
            Block n = b.getRelative(f);
            if (isWallAttached(n, f)) out.add(n);
        }
    }

    private void alert(Player p, Block b) {
        long now = System.currentTimeMillis();
        String key = p.getName() + ":" + b.getType().name();
        Long last = alertCooldown.get(key);
        if (last != null && now - last < plugin.settings().alertCooldownMs()) return;
        alertCooldown.put(key, now);
        if (alertCooldown.size() > 2000) alertCooldown.clear();

        MiniMessage mm = Msg.MM;
        String w = b.getWorld().getName().replace("'", "");
        String text = Msg.PREFIX + "<red>⚠</red> <green>" + mm.escapeTags(p.getName()) + "</green> <gray>broke</gray> <white>"
                + b.getType().name().toLowerCase(Locale.ROOT) + "</white> <dark_gray>[<hover:show_text:'Teleport'><click:run_command:'/up tp "
                + w + " " + b.getX() + " " + b.getY() + " " + b.getZ() + "'>" + b.getX() + " " + b.getY() + " " + b.getZ() + "</click></hover>]</dark_gray>";
        for (UUID id : plugin.getAlertPlayers()) {
            Player target = Bukkit.getPlayer(id);
            if (target != null && target.hasPermission("universalprotect.admin")) target.sendMessage(mm.deserialize(text));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block main = e.getBlock();
        if (on("log.block", main.getWorld())) {
            Set<Block> blocks = new LinkedHashSet<>();
            collect(main, blocks);
            Actor who = Actor.of(e.getPlayer());
            for (Block b : blocks) logBroken(who, b);
        }
        if (plugin.settings().alertBlocks().contains(main.getType())) alert(e.getPlayer(), main);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!on("log.block", e.getBlock().getWorld())) return;
        Actor who = Actor.of(e.getPlayer());
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState rs : multi.getReplacedBlockStates()) {
                Block b = rs.getBlock();
                logPlaced(who, b, rs.getBlockData(), b.getBlockData());
            }
        } else {
            logPlaced(who, e.getBlock(), e.getBlockReplacedState().getBlockData(), e.getBlock().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!on("log.block", e.getLocation())) return;
        Actor who = Actor.of("#" + e.getEntityType().name().toLowerCase(Locale.ROOT));
        String by = null;
        if (e.getEntity() instanceof org.bukkit.entity.TNTPrimed primed && primed.getSource() instanceof Player igniter) by = "by:" + igniter.getName();
        Set<Block> blocks = new LinkedHashSet<>();
        for (Block b : e.blockList()) collect(b, blocks);
        for (Block b : blocks) logBroken(who, b, by);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!on("log.block", e.getBlock().getWorld())) return;
        Set<Block> blocks = new LinkedHashSet<>();
        for (Block b : e.blockList()) collect(b, blocks);
        Actor who = Actor.of("#explosion");
        for (Block b : blocks) logBroken(who, b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!on("log.block", e.getBlock().getWorld())) return;
        Set<Block> blocks = new LinkedHashSet<>();
        collect(e.getBlock(), blocks);
        Actor who = Actor.of("#fire");
        for (Block b : blocks) logBroken(who, b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (on("log.block", e.getBlock().getWorld())) logBroken(Actor.of("#decay"), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        if (!on("log.block", b.getWorld())) return;
        BlockData old = b.getBlockData();
        BlockData nw = e.getBlockData();
        Actor who = e.getEntity() instanceof Player p ? Actor.of(p) : Actor.of("#" + e.getEntityType().name().toLowerCase(Locale.ROOT));
        if (nw.getMaterial().isAir()) logBroken(who, b);
        else logPlaced(who, b, old, nw);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        if (!on("log.block", b.getWorld())) return;
        BlockData old = b.getBlockData();
        Material bucket = e.getBucket();
        Material liquid = bucket == Material.LAVA_BUCKET ? Material.LAVA
                : bucket == Material.POWDER_SNOW_BUCKET ? Material.POWDER_SNOW : Material.WATER;
        BlockData nw;
        if (liquid == Material.WATER && old instanceof Waterlogged) {
            Waterlogged copy = (Waterlogged) old.clone();
            copy.setWaterlogged(true);
            nw = copy;
        } else {
            nw = Bukkit.createBlockData(liquid);
        }
        logPlaced(Actor.of(e.getPlayer()), b, old, nw);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        if (!on("log.block", b.getWorld())) return;
        BlockData old = b.getBlockData();
        Actor who = Actor.of(e.getPlayer());
        if (old instanceof Waterlogged wl) {
            if (!wl.isWaterlogged()) return;
            Waterlogged copy = (Waterlogged) old.clone();
            copy.setWaterlogged(false);
            logPlaced(who, b, old, copy);
        } else {
            logBroken(who, b);
        }
    }

    private static boolean isSpawnEgg(Material m) { return m.name().endsWith("_SPAWN_EGG"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractBlockChange(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();
        if (b == null || e.useInteractedBlock() == Event.Result.DENY || plugin.getInspectingPlayers().contains(p.getUniqueId())) return;

        ItemStack hand = e.getItem();
        if (hand != null && isSpawnEgg(hand.getType())) eggUses.put(p.getUniqueId(), new EggUse(b.getLocation(), System.currentTimeMillis()));

        if (!on("log.block-changes", b.getWorld()) || (hand != null && hand.getType().isBlock())) return;
        BlockData before = b.getBlockData();
        Location loc = b.getLocation();
        Actor who = Actor.of(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            BlockData after = loc.getBlock().getBlockData();
            if (after.getMaterial() == before.getMaterial()) return;
            logChange(who, loc, before.getAsString(), after.getMaterial().isAir() ? AIR : after.getAsString());
        });
    }

    private void logFrameChange(Actor who, ItemFrame f, String before) {
        Location l = f.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String after = f.isValid() ? FrameState.encode(f) : AIR;
            if (!before.equals(after)) logChange(who, l, before, after);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame f) || f.isFixed() || !on("log.frames", f.getLocation())) return;
        Player p = e.getPlayer();
        if (plugin.getInspectingPlayers().contains(p.getUniqueId())) return;
        logFrameChange(Actor.of(p), f, FrameState.encode(f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ItemFrame f) || f.isFixed() || !(e.getDamager() instanceof Player p)) return;
        if (!on("log.frames", f.getLocation()) || plugin.getInspectingPlayers().contains(p.getUniqueId())) return;
        if (f.getItem().getType().isAir()) return;
        logFrameChange(Actor.of(p), f, FrameState.encode(f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null || !on("log.frames", e.getEntity().getLocation())) return;
        Actor who = Actor.of(e.getPlayer());
        if (e.getEntity() instanceof ItemFrame f) {
            logChange(who, f.getLocation(), AIR, FrameState.encode(f));
        } else {
            logAt("frame", who, Actions.FRAME, "placed " + e.getEntity().getType().name().toLowerCase(Locale.ROOT), e.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!on("log.frames", e.getEntity().getLocation())) return;
        Entity remover = e instanceof HangingBreakByEntityEvent byEntity ? byEntity.getRemover() : null;
        Actor who = remover instanceof Player p ? Actor.of(p)
                : Actor.of(remover == null ? "#" + e.getCause().name().toLowerCase(Locale.ROOT) : "#" + remover.getType().name().toLowerCase(Locale.ROOT));
        if (e.getEntity() instanceof ItemFrame f) {
            logChange(who, f.getLocation(), FrameState.encode(f), AIR);
        } else {
            logAt("frame", who, Actions.FRAME, "broke " + e.getEntity().getType().name().toLowerCase(Locale.ROOT), e.getEntity().getLocation());
        }
    }

    private static Map<String, Object[]> tally(Collection<ItemStack> items) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            String key = ItemSerializer.encodeProto(it);
            Object[] cur = m.get(key);
            if (cur == null) m.put(key, new Object[]{it.clone(), it.getAmount()});
            else cur[1] = (Integer) cur[1] + it.getAmount();
        }
        return m;
    }

    private static List<ItemStack> copyOf(Inventory inv) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) out.add(it.clone());
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (inv.getType() == InventoryType.ENDER_CHEST) {
            if (on("log.ender", p.getLocation())) openContainers.put(p.getUniqueId(), new Snapshot(p.getLocation(), true, copyOf(inv)));
            return;
        }
        if (!TRACKED.contains(inv.getType())) return;
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null || !on("log.container", l)) return;
        openContainers.put(p.getUniqueId(), new Snapshot(l, false, copyOf(inv)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Snapshot s = openContainers.remove(p.getUniqueId());
        if (s != null) logDiff(p.getName(), p.getUniqueId().toString(), s, copyOf(e.getInventory()));
    }

    private void logDiff(String user, String uuid, Snapshot s, List<ItemStack> afterItems) {
        Map<String, Object[]> before = tally(s.items()), after = tally(afterItems);
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Actor who = new Actor(user, uuid);
        for (String k : keys) {
            Object[] b = before.get(k), a = after.get(k);
            int delta = (a == null ? 0 : (Integer) a[1]) - (b == null ? 0 : (Integer) b[1]);
            if (delta == 0) continue;
            ItemStack proto = (ItemStack) (a != null ? a[0] : b[0]);
            if (s.ender()) logItem("item", who, delta > 0 ? Actions.ENDER_ADD : Actions.ENDER_REMOVE, proto, Math.abs(delta), s.loc(), null);
            else logItem("container", who, delta > 0 ? Actions.PUT : Actions.TAKE, proto, Math.abs(delta), s.loc(), null);
        }
    }

    public boolean flushContainer(String user, Location loc) {
        Player p = Bukkit.getPlayerExact(user);
        if (p == null || loc == null) return false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Snapshot s = openContainers.get(p.getUniqueId());
            if (s == null || !(loc.getBlock().getState() instanceof Container c)) return;
            List<ItemStack> now = copyOf(c.getInventory());
            logDiff(p.getName(), p.getUniqueId().toString(), s, now);
            openContainers.put(p.getUniqueId(), new Snapshot(s.loc(), s.ender(), now));
        });
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack st = e.getItemDrop().getItemStack();
        Location l = p.getLocation();
        if (on("log.items", l)) logItem("item", Actor.of(p), Actions.DROP, st, st.getAmount(), l, null);
        if (on("log.inventory", l)) logItem("inventory", Actor.of(p), Actions.INV_REMOVE, st, st.getAmount(), l, "drop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !on("log.pickup", p.getLocation())) return;
        ItemStack st = e.getItem().getItemStack();
        logItem("item", Actor.of(p), Actions.PICKUP, st, st.getAmount(), p.getLocation(), null);
        if (on("log.inventory", p.getLocation())) logItem("inventory", Actor.of(p), Actions.INV_ADD, st, st.getAmount(), p.getLocation(), "pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !on("log.inventory", p.getLocation())) return;
        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType().isAir()) return;
        int crafts = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) {
                if (it != null && !it.getType().isAir()) min = Math.min(min, it.getAmount());
            }
            if (min != Integer.MAX_VALUE) crafts = Math.max(1, min);
        }
        logItem("inventory", Actor.of(p), Actions.INV_ADD, result, result.getAmount() * crafts, p.getLocation(), "craft");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent e) {
        Projectile proj = e.getEntity();
        if (!(proj.getShooter() instanceof Player p) || !on("log.items", p.getLocation())) return;
        ItemStack item;
        if (proj instanceof ThrowableProjectile tp) item = tp.getItem();
        else if (proj instanceof Trident) item = new ItemStack(Material.TRIDENT);
        else return;
        logItem("item", Actor.of(p), Actions.THROW, item, 1, p.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !on("log.items", p.getLocation())) return;
        ItemStack ammo = e.getConsumable();
        if (ammo == null || ammo.getType().isAir()) ammo = new ItemStack(Material.ARROW);
        logItem("item", Actor.of(p), Actions.SHOOT, ammo, 1, p.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToolBreak(PlayerItemBreakEvent e) {
        Player p = e.getPlayer();
        if (on("log.items", p.getLocation())) logItem("item", Actor.of(p), Actions.TOOL_BREAK, e.getBrokenItem(), 1, p.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemRemoved(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item item) || e.getCause() != EntityRemoveEvent.Cause.DEATH) return;
        UUID thrower = item.getThrower();
        if (thrower == null || !on("log.items", item.getLocation())) return;
        String name = Bukkit.getOfflinePlayer(thrower).getName();
        if (name == null) return;
        ItemStack st = item.getItemStack();
        logItem("item", new Actor(name, thrower.toString()), Actions.DESTROY, st, st.getAmount(), item.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !on("log.creative", p.getLocation())) return;
        ItemStack cursor = e.getCursor();
        if (cursor.getType().isAir()) return;
        int have = 0;
        for (ItemStack s : p.getInventory().getContents()) if (s != null && s.isSimilar(cursor)) have += s.getAmount();
        int gained = cursor.getAmount() - have;
        if (gained <= 0) return;
        logItem("item", Actor.of(p), Actions.CREATE, cursor, gained, p.getLocation(), "creative");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerTrade(PlayerTradeEvent e) { logTrade(e); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerPurchaseEvent e) { logTrade(e); }

    private void logTrade(PlayerPurchaseEvent e) {
        Player p = e.getPlayer();
        if (!on("log.trades", p.getLocation())) return;
        MerchantRecipe r = e.getTrade();
        Actor who = Actor.of(p);
        ItemStack result = r.getResult();
        logItem("item", who, Actions.BUY, result, result.getAmount(), p.getLocation(), null);
        for (ItemStack in : r.getIngredients()) logItem("item", who, Actions.SELL, in, in.getAmount(), p.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (!on("log.signs", e.getBlock().getWorld())) return;
        StringBuilder sb = new StringBuilder();
        for (Component line : e.lines()) {
            String t = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
        }
        if (sb.length() == 0) return;
        logAt("sign", Actor.of(e.getPlayer()), Actions.SIGN, sb.toString(), e.getBlock().getLocation());
    }

    private static boolean isInteractable(Material m) {
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || Tag.SHULKER_BOXES.isTagged(m)
                || m == Material.LEVER || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.ENDER_CHEST || m == Material.BARREL || m == Material.FURNACE
                || m == Material.BLAST_FURNACE || m == Material.SMOKER || m == Material.HOPPER
                || m == Material.DISPENSER || m == Material.DROPPER || m == Material.BREWING_STAND;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !on("log.interact", b.getWorld()) || e.useInteractedBlock() == Event.Result.DENY) return;
        if (!isInteractable(b.getType()) || plugin.getInspectingPlayers().contains(e.getPlayer().getUniqueId())) return;
        logAt("interact", Actor.of(e.getPlayer()), Actions.USE, b.getType().name().toLowerCase(Locale.ROOT), b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        db().playerSeen(p.getUniqueId(), p.getName());
        if (p.hasPermission("universalprotect.admin") && !plugin.getAlertMuted().contains(p.getUniqueId())) plugin.getAlertPlayers().add(p.getUniqueId());
        if (on("log.sessions", p.getLocation())) logAt("session", Actor.of(p), Actions.JOIN, "Login", p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        openContainers.remove(p.getUniqueId());
        eggUses.remove(p.getUniqueId());
        if (plugin.network() != null) plugin.network().forget(p.getUniqueId());
        if (on("log.sessions", p.getLocation())) logAt("session", Actor.of(p), Actions.QUIT, "Logout", p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (on("log.chat", p.getLocation())) logAt("chat", Actor.of(p), Actions.CHAT, msg, p.getLocation());
        });
    }

    private static final long CMD_BLOCK_CAP = 262_144L;

    private record CmdBefore(int x, int y, int z, BlockData data, String extra) {}

    private static boolean tileLike(Material m) {
        String n = m.name();
        return n.contains("SIGN") || n.contains("CHEST") || n.equals("BARREL") || n.contains("SHULKER") || n.contains("FURNACE")
                || n.equals("SMOKER") || n.equals("HOPPER") || n.equals("DISPENSER") || n.equals("DROPPER")
                || n.equals("BREWING_STAND") || n.equals("CRAFTER");
    }

    private void trackCommand(Actor who, String raw, Location at) {
        try {
            if (at == null || at.getWorld() == null || !on("log.command-blocks", at)) return;
            List<CommandRegions.Region> regions = CommandRegions.parse(raw, at.getX(), at.getY(), at.getZ());
            if (regions.isEmpty()) return;
            long total = 0;
            for (CommandRegions.Region r : regions) total += r.volume();
            if (total > CMD_BLOCK_CAP) {
                plugin.getLogger().warning("Command " + raw + " by " + who.name() + " changes " + total + " blocks; too large to track (limit " + CMD_BLOCK_CAP + ").");
                return;
            }
            World w = at.getWorld();
            Map<Long, CmdBefore> before = new LinkedHashMap<>();
            for (CommandRegions.Region r : regions) {
                for (int x = r.x1(); x <= r.x2(); x++) {
                    for (int z = r.z1(); z <= r.z2(); z++) {
                        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = r.y1(); y <= r.y2(); y++) {
                            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                            long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
                            if (before.containsKey(key)) continue;
                            Block b = w.getBlockAt(x, y, z);
                            BlockData d = b.getBlockData();
                            before.put(key, new CmdBefore(x, y, z, d, tileLike(d.getMaterial()) ? BlockExtras.capture(b.getState()) : null));
                        }
                    }
                }
            }
            String meta = "cmd:" + regions.get(0).label();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (CmdBefore o : before.values()) {
                    if (!w.isChunkLoaded(o.x() >> 4, o.z() >> 4)) continue;
                    Block b = w.getBlockAt(o.x(), o.y(), o.z());
                    BlockData nd = b.getBlockData();
                    if (nd.equals(o.data())) continue;
                    Location l = b.getLocation();
                    String os = o.data().getAsString();
                    if (!o.data().getMaterial().isAir()) emit("block", who, Actions.BROKE, os, l, os, o.extra(), 1, meta);
                    if (!nd.getMaterial().isAir()) emit("block", who, Actions.PLACED, nd.getAsString(), l, os, null, 1, meta);
                }
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Command tracking failed: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        trackCommand(Actor.of(p), e.getMessage(), p.getLocation());
        if (!on("log.commands", p.getLocation())) return;
        String m = e.getMessage();
        String lower = m.toLowerCase(Locale.ROOT);
        for (String ignored : IGNORED_COMMANDS) {
            if (lower.startsWith(ignored) || lower.equals(ignored.trim())) return;
        }
        logAt("command", Actor.of(p), Actions.COMMAND, m, p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        Entity en = e.getEntity();
        if (p == null || en instanceof ItemFrame || !on("log.spawns", en.getLocation())) return;
        logSpawn(Actor.of(p), en);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || !on("log.spawns", e.getLocation())) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, EggUse> en : eggUses.entrySet()) {
            EggUse use = en.getValue();
            if (now - use.time() > 3000 || use.loc().getWorld() != e.getLocation().getWorld()) continue;
            if (use.loc().distanceSquared(e.getLocation()) > 256) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) { logSpawn(Actor.of(p), e.getEntity()); return; }
        }
    }

    private void logSpawn(Actor who, Entity en) {
        if (!spawnLogged.add(en.getUniqueId())) return;
        emit("spawn", who, Actions.SPAWN, en.getType().name().toLowerCase(Locale.ROOT), en.getLocation(), null,
                EntitySerializer.serialize(en), 1, en.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || le instanceof Player || !on("log.kills", le.getLocation())) return;
        if (le.getHealth() - e.getFinalDamage() > 0) return;
        if (lethalCache.size() > 500) lethalCache.clear();
        lethalCache.put(le.getUniqueId(), EntitySerializer.serialize(le));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        String cached = lethalCache.remove(victim.getUniqueId());
        if (!on("log.kills", victim.getLocation())) return;
        Player killer = victim.getKiller();
        Actor who;
        if (killer != null) {
            who = Actor.of(killer);
        } else if (!(victim instanceof Monster) && !(victim instanceof Player)) {
            EntityDamageEvent dc = victim.getLastDamageCause();
            String cause = dc instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager().getType().name()
                    : dc == null ? "unknown" : dc.getCause().name();
            who = Actor.of("#" + cause.toLowerCase(Locale.ROOT));
        } else {
            return;
        }
        boolean player = victim instanceof Player;
        String data = player ? "player:" + victim.getName() : victim.getType().name().toLowerCase(Locale.ROOT);
        String ser = player ? null : (cached != null ? cached : EntitySerializer.serialize(victim));
        emit("kill", who, Actions.KILL, data, victim.getLocation(), null, ser, 1, victim.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;
        if (from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) < 64) return;
        String data = e.getCause().name().toLowerCase(Locale.ROOT) + " -> " + to.getWorld().getName() + " " + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ();
        logAt("teleport", Actor.of(e.getPlayer()), Actions.TELEPORT, data, from);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!on("log.deaths", p.getLocation())) return;
        EntityDamageEvent dc = p.getLastDamageCause();
        String cause = dc == null ? "unknown" : dc.getCause().name().toLowerCase(Locale.ROOT);
        Player killer = p.getKiller();
        if (killer != null) cause += " (by " + killer.getName() + ")";
        logAt("death", Actor.of(p), Actions.DEATH, cause, p.getLocation());
    }
}
