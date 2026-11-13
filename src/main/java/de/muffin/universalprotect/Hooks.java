package de.muffin.universalprotect;

import de.muffin.universalprotect.api.Loc;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Hooks {
    private static final String AIR = "minecraft:air";
    private static final List<String> IGNORED_COMMANDS = List.of(
            "/login", "/l ", "/register", "/reg ", "/changepassword", "/unregister", "/up ", "/universalprotect");
    private static final Set<String> TRACKED_MENUS = Set.of("generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4",
            "generic_9x5", "generic_9x6", "generic_3x3", "hopper", "shulker_box", "furnace", "blast_furnace", "smoker", "brewing_stand");

    static Hooks I;

    public static Hooks instance() { return I; }

    public record Actor(String name, String uuid) {
        public static Actor of(ServerPlayer p) { return new Actor(Mc.name(p), p.getUUID().toString()); }
        public static Actor of(String name) { return new Actor(name, null); }
    }

    public enum Kind { BREAK, PLACE, USE, FIRE, ENTITY }

    record Ctx(Actor who, ServerPlayer player, Kind kind, int tick) {}

    private record Snapshot(String world, BlockPos pos, boolean ender, List<ItemStack> items) {}
    private record Click(String world, BlockPos pos, int tick) {}

    private final UniversalProtect plugin;
    private final Map<UUID, Snapshot> openContainers = new HashMap<>();
    private final Map<UUID, Click> lastClick = new HashMap<>();
    private final Map<UUID, String> lethalCache = new HashMap<>();
    private final Map<UUID, UUID> throwers = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, UUID> e) { return size() > 512; }
    };
    private final Map<UUID, UUID> frameAttackers = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, UUID> e) { return size() > 128; }
    };
    private final Map<String, Long> alertCooldown = new ConcurrentHashMap<>();

    public Hooks(UniversalProtect plugin) { this.plugin = plugin; I = this; }

    private DatabaseManager db() { return plugin.db(); }

    private boolean on(String key, Level level) { return plugin.settings().opt(key, level == null ? null : Mc.key(level)); }

    private static final ThreadLocal<int[]> SUPPRESS = ThreadLocal.withInitial(() -> new int[1]);

    public static void suppress(Runnable r) {
        SUPPRESS.get()[0]++;
        try { r.run(); } finally { SUPPRESS.get()[0]--; }
    }

    static boolean suppressed() { return SUPPRESS.get()[0] > 0; }

    private static final ThreadLocal<ArrayDeque<Ctx>> CTX = ThreadLocal.withInitial(ArrayDeque::new);

    private static int tickNow() { var s = Mc.serverOrNull(); return s == null ? 0 : s.getTickCount(); }

    public static void push(ServerPlayer player, Actor who, Kind kind) {
        CTX.get().push(new Ctx(who, player, kind, tickNow()));
    }

    public static void push(ServerPlayer player, Kind kind) { push(player, Actor.of(player), kind); }

    public static void pop() {
        ArrayDeque<Ctx> d = CTX.get();
        if (!d.isEmpty()) d.pop();
    }

    static Ctx ctx() {
        ArrayDeque<Ctx> d = CTX.get();
        if (d.isEmpty()) return null;
        Ctx c = d.peek();
        if (c.tick() != tickNow()) { d.clear(); return null; }
        return c;
    }

    private void emit(String type, Actor who, String action, String data, String world, BlockPos p,
                      String oldData, String oldExtra, int amount, String meta) {
        if (world == null || p == null) return;
        db().log(new LogEntry(System.currentTimeMillis(), type, who.name(), who.uuid(), action, data, world,
                p.getX(), p.getY(), p.getZ(), oldData, oldExtra, amount, meta));
    }

    private void logAt(String type, Actor who, String action, String data, Level level, BlockPos p) {
        emit(type, who, action, data, Mc.key(level), p, null, null, 1, null);
    }

    private void logItem(String type, Actor who, String action, ItemStack stack, int amount, Level level, BlockPos p, String meta) {
        if (stack == null || stack.isEmpty() || amount <= 0) return;
        emit(type, who, action, ItemSerializer.text(stack, amount), Mc.key(level), p, null, ItemSerializer.encodeProto(stack), amount, meta);
    }

    private void logBroken(Actor who, Level level, BlockPos pos, BlockState old) {
        if (old.isAir()) return;
        String s = Mc.stateString(old);
        emit("block", who, Actions.BROKE, s, Mc.key(level), pos, s, Mc.captureBlockEntity(level, pos), 1, null);
    }

    private void logPlaced(Actor who, Level level, BlockPos pos, BlockState old, BlockState neu) {
        if (neu.isAir()) return;
        emit("block", who, Actions.PLACED, Mc.stateString(neu), Mc.key(level), pos, Mc.stateString(old), null, 1, null);
    }

    private void logChange(Actor who, String world, BlockPos pos, String oldState, String newState) {
        if (!oldState.equals(AIR)) emit("block", who, Actions.BROKE, oldState, world, pos, oldState, null, 1, null);
        if (!newState.equals(AIR)) emit("block", who, Actions.PLACED, newState, world, pos, oldState, null, 1, null);
    }

    public void blockChange(Level level, BlockPos pos, BlockState neu) {
        if (suppressed()) return;
        Ctx c = ctx();
        if (c == null || !(level instanceof ServerLevel)) return;
        BlockState old = level.getBlockState(pos);
        if (old == neu) return;
        boolean oldEmpty = old.isAir() || old.getBlock() instanceof LiquidBlock;
        boolean newEmpty = neu.isAir() || neu.getBlock() instanceof LiquidBlock;
        BlockPos p = pos.immutable();

        switch (c.kind()) {
            case FIRE -> {
                if (!on("log.block", level) || old.isAir() || old.getBlock() instanceof BaseFireBlock || old.getBlock() instanceof LiquidBlock) return;
                logBroken(c.who(), level, p, old);
            }
            case PLACE -> {
                if (!on("log.block", level) || neu.isAir()) return;
                if (old.getBlock() == neu.getBlock() && !(old.getBlock() instanceof LiquidBlock)) {
                    if (on("log.block-changes", level)) logChange(c.who(), Mc.key(level), p, Mc.stateString(old), Mc.stateString(neu));
                    return;
                }
                logPlaced(c.who(), level, p, old, neu);
            }
            case BREAK -> {
                if (!on("log.block", level) || old.isAir()) return;
                if (c.player() != null && plugin.settings().alertBlocks().contains(Mc.blockId(old.getBlock()))) alert(c.player(), level, p, Mc.blockId(old.getBlock()));
                if (newEmpty) logBroken(c.who(), level, p, old);
                else logChange(c.who(), Mc.key(level), p, Mc.stateString(old), Mc.stateString(neu));
            }
            case USE, ENTITY -> {
                if (oldEmpty && newEmpty) return;
                if (old.getBlock() == neu.getBlock() && c.kind() == Kind.USE) return;
                boolean changeOnly = !oldEmpty && !newEmpty;
                if (!on(changeOnly ? "log.block-changes" : "log.block", level)) return;
                if (oldEmpty) logPlaced(c.who(), level, p, old, neu);
                else if (newEmpty) logBroken(c.who(), level, p, old);
                else logChange(c.who(), Mc.key(level), p, Mc.stateString(old), Mc.stateString(neu));
            }
        }
    }

    private void alert(ServerPlayer p, Level level, BlockPos pos, String blockId) {
        long now = System.currentTimeMillis();
        String key = Mc.name(p) + ":" + blockId;
        Long last = alertCooldown.get(key);
        if (last != null && now - last < plugin.settings().alertCooldownMs()) return;
        alertCooldown.put(key, now);
        if (alertCooldown.size() > 2000) alertCooldown.clear();
        String w = Mc.key(level);
        String text = Msg.PREFIX + "<red>⚠</red> <green>" + Mini.escape(Mc.name(p)) + "</green> <gray>broke</gray> <white>"
                + Msg.cleanBlock(blockId) + "</white> <dark_gray>[<hover:show_text:'Teleport'><click:run_command:'/up tp "
                + w + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "'>" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "</click></hover>]</dark_gray>";
        for (UUID id : plugin.getAlertPlayers()) {
            ServerPlayer t = Mc.player(id);
            if (t != null && Perm.has(t.createCommandSourceStack(), "universalprotect.admin")) t.sendSystemMessage(Mini.parse(text));
        }
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> CTX.get().clear());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onQuit(handler.getPlayer()));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onChat(sender, message.signedContent()));

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> { lethal(entity); return true; });
        ServerLivingEntityEvents.AFTER_DEATH.register(this::afterDeath);

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (plugin.getInspectingPlayers().contains(sp.getUUID())) {
                inspect(sp, world, hit.getBlockPos().relative(hit.getDirection()));
                return InteractionResult.FAIL;
            }
            lastClick.put(sp.getUUID(), new Click(Mc.key(world), hit.getBlockPos().immutable(), tickNow()));
            if (on("log.interact", world) && interactable(world.getBlockState(hit.getBlockPos()))) {
                logAt("interact", Actor.of(sp), Actions.USE, Msg.cleanBlock(Mc.blockId(world.getBlockState(hit.getBlockPos()).getBlock())), world, hit.getBlockPos());
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!plugin.getInspectingPlayers().contains(sp.getUUID())) return InteractionResult.PASS;
            inspect(sp, world, pos);
            return InteractionResult.FAIL;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof ItemFrame f)) return InteractionResult.PASS;
            if (plugin.getInspectingPlayers().contains(sp.getUUID())) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && on("log.frames", world)) frameChange(Actor.of(sp), f);
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (entity instanceof HangingEntity) frameAttackers.put(entity.getUUID(), sp.getUUID());
            if (entity instanceof ItemFrame f && on("log.frames", world) && !plugin.getInspectingPlayers().contains(sp.getUUID()) && !f.getItem().isEmpty()) {
                frameChange(Actor.of(sp), f);
            }
            return InteractionResult.PASS;
        });
    }

    private void inspect(ServerPlayer p, Level world, BlockPos target) {
        if (!Perm.has(p.createCommandSourceStack(), "universalprotect.use")) {
            Msg.err(new Sender(p.createCommandSourceStack()), "You don't have permission.");
            return;
        }
        plugin.db().lookup(new Sender(p.createCommandSourceStack()), Query.at(world, target, plugin.settings().defaultLines()));
    }

    private static boolean interactable(BlockState s) {
        String id = Mc.blockId(s.getBlock()).replace("minecraft:", "");
        return id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("_fence_gate") || id.endsWith("_button")
                || id.endsWith("shulker_box") || id.equals("lever") || id.equals("chest") || id.equals("trapped_chest")
                || id.equals("ender_chest") || id.equals("barrel") || id.equals("furnace") || id.equals("blast_furnace")
                || id.equals("smoker") || id.equals("hopper") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("brewing_stand");
    }

    private void frameChange(Actor who, ItemFrame f) {
        String before = FrameState.encode(f);
        String world = Mc.key(f.level());
        BlockPos pos = f.blockPosition();
        Sched.later(1L, () -> {
            String after = f.isRemoved() ? AIR : FrameState.encode(f);
            if (!before.equals(after)) logChange(who, world, pos, before, after);
        });
    }

    public void entityAdded(ServerLevel level, Entity e) {
        if (suppressed()) return;
        Ctx c = ctx();
        String path = Mc.entityPath(e);

        if (e instanceof Projectile proj && proj.getOwner() instanceof ServerPlayer sp && on("log.items", level)) {
            String item = switch (path) {
                case "arrow", "spectral_arrow" -> "arrow";
                case "trident", "snowball", "egg", "ender_pearl", "experience_bottle", "splash_potion", "lingering_potion", "firework_rocket" -> path;
                default -> null;
            };
            if (item != null) {
                Item it = Mc.itemByPath(item);
                ItemStack st = it == null ? ItemStack.EMPTY : new ItemStack(it);
                logItem("item", Actor.of(sp), path.endsWith("arrow") ? Actions.SHOOT : Actions.THROW, st, 1, level, sp.blockPosition(), null);
            }
            return;
        }
        if (c == null || c.player() == null || !on("log.spawns", level) && !on("log.frames", level)) return;
        if (e instanceof ItemFrame f) {
            if (on("log.frames", level)) logChange(c.who(), Mc.key(level), f.blockPosition(), AIR, FrameState.encode(f));
        } else if (e instanceof HangingEntity) {
            if (on("log.frames", level)) logAt("frame", c.who(), Actions.FRAME, "placed " + path, level, e.blockPosition());
        } else if (!(e instanceof ItemEntity) && !(e instanceof Projectile) && on("log.spawns", level)
                && (e instanceof LivingEntity || path.contains("boat") || path.contains("minecart") || path.equals("armor_stand")
                || path.equals("end_crystal"))) {
            if (e instanceof Player) return;
            emit("spawn", c.who(), Actions.SPAWN, path, Mc.key(level), e.blockPosition(), null, Mc.serializeEntity(e), 1, e.getUUID().toString());
        }
    }

    public void entityKilled(Entity e) {
        if (suppressed() || !(e.level() instanceof ServerLevel level)) return;
        if (e instanceof ItemEntity item) {
            UUID thrower = throwers.remove(item.getUUID());
            if (thrower == null || !on("log.items", level)) return;
            ServerPlayer sp = Mc.player(thrower);
            Actor who = sp != null ? Actor.of(sp) : new Actor("unknown", thrower.toString());
            ItemStack st = item.getItem();
            logItem("item", who, Actions.DESTROY, st, st.getCount(), level, item.blockPosition(), null);
        } else if (e instanceof HangingEntity h && on("log.frames", level)) {
            UUID a = frameAttackers.remove(h.getUUID());
            ServerPlayer sp = a == null ? null : Mc.player(a);
            Actor who = sp != null ? Actor.of(sp) : Actor.of("#unknown");
            if (e instanceof ItemFrame f) logChange(who, Mc.key(level), f.blockPosition(), FrameState.encode(f), AIR);
            else logAt("frame", who, Actions.FRAME, "broke " + Mc.entityPath(e), level, e.blockPosition());
        }
    }

    private static boolean isOtherContainer(Slot s, Player p) { return s.container != p.getInventory(); }

    private List<ItemStack> collect(AbstractContainerMenu menu, Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (!isOtherContainer(s, p)) continue;
            ItemStack it = s.getItem();
            if (!it.isEmpty()) out.add(it.copy());
        }
        return out;
    }

    public void menuOpened(ServerPlayer p, AbstractContainerMenu menu) {
        try {
            String type = net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()).getPath();
            if (!TRACKED_MENUS.contains(type)) return;
        } catch (Throwable t) {
            return;
        }
        Level level = p.level();
        boolean ender = false;
        BlockPos pos = null;
        for (Slot s : menu.slots) {
            if (!isOtherContainer(s, p)) continue;
            if (s.container.getClass().getSimpleName().contains("EnderChest")) ender = true;
            if (s.container instanceof net.minecraft.world.level.block.entity.BlockEntity be && pos == null) pos = be.getBlockPos();

        }
        if (ender) {
            if (!on("log.ender", level)) return;
            pos = p.blockPosition();
        } else {
            Click c = lastClick.get(p.getUUID());
            if (pos == null && c != null && c.tick() >= tickNow() - 100 && c.world().equals(Mc.key(level))) pos = c.pos();
            if (pos == null || !on("log.container", level)) return;
        }
        openContainers.put(p.getUUID(), new Snapshot(Mc.key(level), pos.immutable(), ender, collect(menu, p)));
    }

    public void menuClosed(ServerPlayer p) {
        Snapshot s = openContainers.remove(p.getUUID());
        if (s == null) return;
        logDiff(Mc.name(p), p.getUUID().toString(), s, collect(p.containerMenu, p));
    }

    private static Map<String, Object[]> tally(Collection<ItemStack> items) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (ItemStack it : items) {
            if (it == null || it.isEmpty()) continue;
            String key = ItemSerializer.encodeProto(it);
            if (key == null) continue;
            Object[] cur = m.get(key);
            if (cur == null) m.put(key, new Object[]{it.copy(), it.getCount()});
            else cur[1] = (Integer) cur[1] + it.getCount();
        }
        return m;
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
            int n = Math.abs(delta);
            if (proto.isEmpty()) continue;
            String action = s.ender() ? (delta > 0 ? Actions.ENDER_ADD : Actions.ENDER_REMOVE) : (delta > 0 ? Actions.PUT : Actions.TAKE);
            emit(s.ender() ? "item" : "container", who, action, ItemSerializer.text(proto, n), s.world(), s.pos(), null,
                    ItemSerializer.encodeProto(proto), n, null);
        }
    }

    public boolean flushContainer(String user, Loc loc) {
        ServerPlayer p = Mc.player(user);
        if (p == null || loc == null) return false;
        Sched.main(() -> {
            Snapshot s = openContainers.get(p.getUUID());
            if (s == null) return;
            List<ItemStack> now = collect(p.containerMenu, p);
            logDiff(Mc.name(p), p.getUUID().toString(), s, now);
            openContainers.put(p.getUUID(), new Snapshot(s.world(), s.pos(), s.ender(), now));
        });
        return true;
    }

    public void drop(ServerPlayer p, ItemStack st) {
        if (suppressed() || st.isEmpty()) return;
        Level l = p.level();
        if (on("log.items", l)) logItem("item", Actor.of(p), Actions.DROP, st, st.getCount(), l, p.blockPosition(), null);
        if (on("log.inventory", l)) logItem("inventory", Actor.of(p), Actions.INV_REMOVE, st, st.getCount(), l, p.blockPosition(), "drop");
    }

    public void dropped(ServerPlayer p, ItemEntity e) { if (e != null) throwers.put(e.getUUID(), p.getUUID()); }

    public void pickup(ServerPlayer p, ItemStack stack, int amount) {
        if (suppressed() || amount <= 0) return;
        Level l = p.level();
        if (!on("log.pickup", l)) return;
        logItem("item", Actor.of(p), Actions.PICKUP, stack, amount, l, p.blockPosition(), null);
        if (on("log.inventory", l)) logItem("inventory", Actor.of(p), Actions.INV_ADD, stack, amount, l, p.blockPosition(), "pickup");
    }

    public void craft(ServerPlayer p, ItemStack result) {
        if (suppressed() || result.isEmpty() || !on("log.inventory", p.level())) return;
        logItem("inventory", Actor.of(p), Actions.INV_ADD, result, result.getCount(), p.level(), p.blockPosition(), "craft");
    }

    public void toolBreak(ServerPlayer p, Item item) {
        if (!on("log.items", p.level())) return;
        logItem("item", Actor.of(p), Actions.TOOL_BREAK, new ItemStack(item), 1, p.level(), p.blockPosition(), null);
    }

    public void creative(ServerPlayer p, ItemStack cursor) {
        if (cursor == null || cursor.isEmpty() || !on("log.items", p.level())) return;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack have = p.getInventory().getItem(i);
            if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, cursor)) return;
        }
        logItem("item", Actor.of(p), Actions.CREATE, cursor, cursor.getCount(), p.level(), p.blockPosition(), null);
    }

    public void trade(ServerPlayer p, ItemStack result, ItemStack costA, ItemStack costB) {
        if (!on("log.trades", p.level())) return;
        Actor who = Actor.of(p);
        logItem("item", who, Actions.BUY, result, result.getCount(), p.level(), p.blockPosition(), null);
        logItem("item", who, Actions.SELL, costA, costA.getCount(), p.level(), p.blockPosition(), null);
        logItem("item", who, Actions.SELL, costB, costB.getCount(), p.level(), p.blockPosition(), null);
    }

    public void sign(ServerPlayer p, Level level, BlockPos pos, String[] lines) {
        if (!on("log.signs", level)) return;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
        }
        if (sb.length() == 0) return;
        logAt("sign", Actor.of(p), Actions.SIGN, sb.toString(), level, pos);
    }

    private void onJoin(ServerPlayer p) {
        db().playerSeen(p.getUUID(), Mc.name(p));
        if (Perm.has(p.createCommandSourceStack(), "universalprotect.admin") && !plugin.getAlertMuted().contains(p.getUUID())) plugin.getAlertPlayers().add(p.getUUID());
        if (on("log.sessions", p.level())) logAt("session", Actor.of(p), Actions.JOIN, "Login", p.level(), p.blockPosition());
    }

    private void onQuit(ServerPlayer p) {
        openContainers.remove(p.getUUID());
        lastClick.remove(p.getUUID());
        plugin.getInspectingPlayers().remove(p.getUUID());
        plugin.getAlertPlayers().remove(p.getUUID());
        if (plugin.network() != null) plugin.network().forget(p.getUUID());
        if (on("log.sessions", p.level())) logAt("session", Actor.of(p), Actions.QUIT, "Logout", p.level(), p.blockPosition());
    }

    private void onChat(ServerPlayer p, String msg) {
        if (on("log.chat", p.level())) logAt("chat", Actor.of(p), Actions.CHAT, msg, p.level(), p.blockPosition());
    }

    private static final long CMD_BLOCK_CAP = 262_144L;

    private record CmdBefore(BlockPos pos, BlockState state, String extra) {}

    private void trackCommand(ServerPlayer p, String raw) {
        try {
            if (!(p.level() instanceof ServerLevel level) || !on("log.command-blocks", level)) return;
            List<CommandRegions.Region> regions = CommandRegions.parse(raw, p.getX(), p.getY(), p.getZ());
            if (regions.isEmpty()) return;
            long total = 0;
            for (CommandRegions.Region r : regions) total += r.volume();
            if (total > CMD_BLOCK_CAP) {
                plugin.getLogger().warning("Command " + raw + " by " + Mc.name(p) + " changes " + total + " blocks; too large to track (limit " + CMD_BLOCK_CAP + ").");
                return;
            }
            Map<Long, CmdBefore> before = new LinkedHashMap<>();
            for (CommandRegions.Region r : regions) {
                for (int x = r.x1(); x <= r.x2(); x++) {
                    for (int z = r.z1(); z <= r.z2(); z++) {
                        for (int y = r.y1(); y <= r.y2(); y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (before.containsKey(pos.asLong()) || !level.hasChunkAt(pos)) continue;
                            BlockState st = level.getBlockState(pos);
                            before.put(pos.asLong(), new CmdBefore(pos, st, st.hasBlockEntity() ? Mc.captureBlockEntity(level, pos) : null));
                        }
                    }
                }
            }
            Actor who = Actor.of(p);
            String world = Mc.key(level);
            String meta = "cmd:" + regions.get(0).label();
            Sched.later(1, () -> {
                for (CmdBefore o : before.values()) {
                    BlockState nd = level.getBlockState(o.pos());
                    if (nd.equals(o.state())) continue;
                    String os = Mc.stateString(o.state());
                    if (!o.state().isAir()) emit("block", who, Actions.BROKE, os, world, o.pos(), os, o.extra(), 1, meta);
                    if (!nd.isAir()) emit("block", who, Actions.PLACED, Mc.stateString(nd), world, o.pos(), os, null, 1, meta);
                }
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Command tracking failed: " + ex.getMessage());
        }
    }

    public void command(ServerPlayer p, String raw) {
        trackCommand(p, raw);
        if (!on("log.commands", p.level())) return;
        String m = raw.startsWith("/") ? raw : "/" + raw;
        String lower = m.toLowerCase(Locale.ROOT);
        for (String ignored : IGNORED_COMMANDS) {
            if (lower.startsWith(ignored) || lower.equals(ignored.trim())) return;
        }
        logAt("command", Actor.of(p), Actions.COMMAND, m, p.level(), p.blockPosition());
    }

    private void lethal(LivingEntity e) {
        if (e instanceof ServerPlayer || !on("log.kills", e.level())) return;
        if (lethalCache.size() > 500) lethalCache.clear();
        lethalCache.put(e.getUUID(), Mc.serializeEntity(e));
    }

    private void afterDeath(LivingEntity victim, DamageSource source) {
        String cached = lethalCache.remove(victim.getUUID());
        Level level = victim.level();
        if (suppressed()) return;
        if (victim instanceof ServerPlayer sp) {
            if (on("log.deaths", level)) {
                String cause = source.getMsgId();
                if (source.getEntity() instanceof ServerPlayer k) cause += " (by " + Mc.name(k) + ")";
                logAt("death", Actor.of(sp), Actions.DEATH, cause, level, sp.blockPosition());
            }
        }
        if (!on("log.kills", level)) return;
        Actor who;
        if (source.getEntity() instanceof ServerPlayer killer) {
            who = Actor.of(killer);
        } else if (!(victim instanceof Monster) && !(victim instanceof ServerPlayer)) {
            String cause = source.getEntity() != null ? Mc.entityPath(source.getEntity()) : source.getMsgId();
            who = Actor.of("#" + cause.toLowerCase(Locale.ROOT));
        } else {
            return;
        }
        boolean player = victim instanceof ServerPlayer;
        String data = player ? "player:" + Mc.name((ServerPlayer) victim) : Mc.entityPath(victim);
        String ser = player ? null : (cached != null ? cached : Mc.serializeEntity(victim));
        emit("kill", who, Actions.KILL, data, Mc.key(level), victim.blockPosition(), null, ser, 1, victim.getUUID().toString());
    }
}
