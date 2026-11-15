package de.muffin.universalprotect;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.sql.SQLException;
import java.util.*;

public final class RollbackEngine {
    private static final String AIR = "minecraft:air";
    private static final int PREVIEW_TOP = 8;
    private static final int RECON_MAX = 300;
    private static final int PREVIEW_TOP_VERBOSE = 30;

    private record Pos(int x, int y, int z) {}

    private sealed interface Op permits BlockOp, ContainerOp, EntityOp, InvOp, ReconOp {}

    private record BlockOp(String world, int x, int y, int z, String data, String extra, String facing) implements Op {
        boolean frame() { return FrameState.isFrame(data) || facing != null; }
    }

    private record ContainerOp(String world, int x, int y, int z, String item, int amount, boolean add) implements Op {}

    private record EntityOp(String world, int x, int y, int z, boolean spawn, String type, String data, String uuid) implements Op {}

    private record InvOp(String uuid, String player, String item, int amount, boolean add) implements Op {}

    /** A PUT into another container by a player who had picked up items of a broken container. */
    private record PutRef(long id, String world, int x, int y, int z, String item, int[] left) {}

    private record Pickup(long id, String uuid, String player, String item, int amount, List<PutRef> puts) {}

    /** Keeps a restored container from duplicating items that were dropped, picked up or moved after it was broken. */
    private record ReconOp(String world, int x, int y, int z, String data, String extra, List<Pickup> pickups) implements Op {}

    private static final class Plan {
        final boolean restore;
        final Query query;
        final Map<String, Map<Pos, BlockOp>> blocks = new LinkedHashMap<>();
        final List<ContainerOp> containers = new ArrayList<>();
        final List<EntityOp> entities = new ArrayList<>();
        final List<InvOp> inventories = new ArrayList<>();
        final List<ReconOp> recon = new ArrayList<>();
        final List<Long> rowIds = new ArrayList<>();
        int rows, blockRows, containerRows, entityRows, inventoryRows;

        Plan(boolean restore, Query query) { this.restore = restore; this.query = query; }

        int size() {
            int n = containers.size() + entities.size() + inventories.size() + recon.size();
            for (Map<Pos, BlockOp> m : blocks.values()) n += m.size();
            return n;
        }

        List<ContainerOp> early() {
            List<ContainerOp> out = new ArrayList<>();
            for (ContainerOp c : containers) {
                if (c.add()) continue;
                Map<Pos, BlockOp> m = blocks.get(c.world());
                BlockOp b = m == null ? null : m.get(new Pos(c.x(), c.y(), c.z()));
                if (b != null && AIR.equals(b.data())) out.add(c);
            }
            return out;
        }

        int prefix() { return recon.size() + early().size(); }

        List<Op> flatten() {
            List<Op> all = new ArrayList<>(size());
            all.addAll(recon);
            List<ContainerOp> early = early();
            all.addAll(early);
            Set<ContainerOp> earlySet = Collections.newSetFromMap(new IdentityHashMap<>());
            earlySet.addAll(early);
            for (Map<Pos, BlockOp> m : blocks.values()) all.addAll(m.values());
            for (ContainerOp c : containers) if (!earlySet.contains(c)) all.add(c);
            all.addAll(entities);
            all.addAll(inventories);
            return all;
        }
    }

    private static final class Stats {
        int blockTotal, blockChanged, blockUnchanged, blockFailed, frames, worldMissing;
        final Map<String, Integer> transitions = new HashMap<>();
        final Map<String, Integer> perWorld = new TreeMap<>();
        int[] min, max;
        final Map<String, int[]> containerItems = new TreeMap<>();
        final Set<String> containerSpots = new HashSet<>();
        int containerDone, containerSkipped;
        final Map<String, int[]> entityTypes = new TreeMap<>();
        int entitySkipped;
        final Map<String, int[]> invPlayers = new TreeMap<>();
        int invDone, invOffline;
        int reconGround, reconInv, reconMoved, reconQueued, reconMissed;
        final List<Object[]> previewBlocks = new ArrayList<>();
        long millis;

        void bounds(int x, int y, int z) {
            if (min == null) { min = new int[]{x, y, z}; max = new int[]{x, y, z}; return; }
            min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
            max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
        }
    }

    private record Pending(Plan plan, List<Object[]> shown, Sched.Handle expiry) {}

    private record UndoState(Plan inverse, List<Long> flagIds, boolean flagValue) {}

    private final UniversalProtect plugin;
    private final DatabaseManager db;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, UndoState> undo = new HashMap<>();

    public RollbackEngine(UniversalProtect plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void start(Sender sender, Query q, boolean restore) {
        db.submit(() -> {
            try {
                db.flushNow();
                Plan plan = buildPlan(q, restore);
                if (plan.rows == 0 || plan.size() == 0) {
                    Msg.err(sender, "Nothing to " + (restore ? "restore" : "roll back") + ": no matching log entries.");
                    return;
                }
                boolean preview = q.hasTag("preview");
                Sched.main(() -> {
                    if (preview) runDry(sender, plan);
                    else runReal(sender, plan, restore ? "Restore" : "Rollback", true);
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Rollback/Restore failed: " + e.getMessage());
                e.printStackTrace();
                Msg.err(sender, "Action failed, see console.");
            }
        });
    }

    public void apply(Sender sender) {
        Pending p = pending.remove(sender.key());
        if (p == null) { Msg.err(sender, "No pending preview. Use /up rollback ... #preview first."); return; }
        p.expiry().cancel();
        revertPreview(sender, p.shown());
        runReal(sender, p.plan(), p.plan().restore ? "Restore" : "Rollback", true);
    }

    public void cancel(Sender sender) {
        Pending p = pending.remove(sender.key());
        if (p == null) { Msg.err(sender, "No pending preview."); return; }
        p.expiry().cancel();
        revertPreview(sender, p.shown());
        Msg.ok(sender, "Preview cancelled.");
    }

    public void undo(Sender sender) {
        UndoState u = undo.remove(sender.key());
        if (u == null || u.inverse().size() == 0) { Msg.err(sender, "No pending actions to undo."); return; }
        runUndo(sender, u);
    }

    private Plan buildPlan(Query q, boolean restore) throws SQLException {
        Set<String> acts = new LinkedHashSet<>();
        if (q.actions == null) acts.addAll(Actions.ROLLBACKABLE);
        else for (String a : q.actions) if (Actions.ROLLBACKABLE.contains(a)) acts.add(a);
        Plan plan = new Plan(restore, q);
        if (acts.isEmpty()) return plan;

        Query rq = q.copy();
        rq.actions = acts;
        List<LogRow> rows = db.selectRows(rq, restore, 0, 0,
                "(action IN ('" + Actions.PLACED + "','" + Actions.BROKE + "') OR rolled_back = ?)", List.of(restore ? 1 : 0));

        List<Object[]> reconCandidates = new ArrayList<>();
        for (LogRow r : rows) {
            boolean used = false;
            String a = r.action();
            switch (a) {
                case Actions.PLACED, Actions.BROKE -> {
                    String target, extra = null;
                    if (restore) {
                        target = Actions.BROKE.equals(a) ? AIR : r.data();
                    } else {
                        target = r.oldData() != null ? r.oldData() : AIR;
                        extra = r.oldExtra();
                    }
                    if (target == null) break;
                    String facing = FrameState.facingOf(r.data());
                    if (facing == null) facing = FrameState.facingOf(r.oldData());
                    BlockOp op = new BlockOp(r.world(), r.x(), r.y(), r.z(), target, extra, facing);
                    plan.blocks.computeIfAbsent(r.world(), k -> new LinkedHashMap<>()).put(new Pos(r.x(), r.y(), r.z()), op);
                    plan.blockRows++;
                    if (!restore && Actions.BROKE.equals(a) && extra != null && containerBlock(target) && reconCandidates.size() < RECON_MAX
                            && plugin.settings().reconcileContainers()) {
                        reconCandidates.add(new Object[]{r, target, extra});
                    }
                    used = true;
                }
                case Actions.PUT, Actions.TAKE -> {
                    if (r.oldExtra() == null) break;
                    boolean put = Actions.PUT.equals(a);
                    plan.containers.add(new ContainerOp(r.world(), r.x(), r.y(), r.z(), r.oldExtra(), Math.max(1, r.amount()), restore == put));
                    plan.containerRows++;
                    used = true;
                }
                case Actions.INV_ADD, Actions.INV_REMOVE -> {
                    if (r.oldExtra() == null || r.uuid() == null) break;
                    boolean added = Actions.INV_ADD.equals(a);
                    plan.inventories.add(new InvOp(r.uuid(), r.player(), r.oldExtra(), Math.max(1, r.amount()), restore == added));
                    plan.inventoryRows++;
                    used = true;
                }
                case Actions.KILL, Actions.SPAWN -> {
                    boolean kill = Actions.KILL.equals(a);
                    boolean respawn = restore != kill;
                    if (respawn && r.oldExtra() == null) break;
                    if (!respawn && r.meta() == null) break;
                    String type = r.data() == null ? "?" : r.data().split(":")[0].toLowerCase(Locale.ROOT);
                    plan.entities.add(new EntityOp(r.world(), r.x(), r.y(), r.z(), respawn, type, r.oldExtra(), r.meta()));
                    plan.entityRows++;
                    used = true;
                }
                default -> {  }
            }
            if (used) { plan.rowIds.add(r.id()); plan.rows++; }
        }
        Set<Long> planned = new HashSet<>(plan.rowIds);
        Set<Long> reconUsed = new HashSet<>();
        Map<Long, PutRef> putCache = new HashMap<>();
        for (Object[] c : reconCandidates) {
            plan.recon.add(reconFor((LogRow) c[0], (String) c[1], (String) c[2], reconUsed, planned, putCache));
        }
        return plan;
    }

    private PendingReclaim reclaimStore;

    private synchronized PendingReclaim reclaim() {
        if (reclaimStore == null) reclaimStore = new PendingReclaim(plugin.getDataFolder());
        return reclaimStore;
    }

    private static boolean same(ItemStack a, ItemStack b) { return ItemStack.isSameItemSameComponents(a, b); }

    private static int takeFrom(ServerPlayer pl, ItemStack proto, int n) {
        return Mc.extract(pl.getInventory(), proto.copyWithCount(n), 41);
    }

    public void applyPending(ServerPlayer p) {
        try {
            for (PendingReclaim.Entry e : reclaim().take(p.getUUID().toString())) {
                ItemStack proto = ItemSerializer.decode(e.item());
                if (proto == null) continue;
                int removed = takeFrom(p, proto, e.amount());
                plugin.getLogger().info("[Reclaim] Took back " + removed + " of " + e.amount() + " x "
                        + Mc.itemPath(proto) + " from " + Mc.name(p) + " (container rollback while the player was offline).");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Reclaim] Failed for " + Mc.name(p) + ": " + ex.getMessage());
        }
    }

    private static boolean containerBlock(String state) {
        if (state == null) return false;
        String s = state.toLowerCase(Locale.ROOT);
        int br = s.indexOf('[');
        if (br > 0) s = s.substring(0, br);
        if (s.contains("ender_chest")) return false;
        return s.contains("chest") || s.contains("barrel") || s.contains("shulker_box") || s.contains("hopper")
                || s.contains("dropper") || s.contains("dispenser") || s.contains("furnace") || s.contains("smoker")
                || s.contains("brewing_stand") || s.contains("crafter");
    }

    private ReconOp reconFor(LogRow r, String data, String extra, Set<Long> used, Set<Long> planned, Map<Long, PutRef> putCache) throws SQLException {
        long until = r.time() + plugin.settings().reconcileWindowMs();
        Query pq = new Query();
        pq.type = "inventory";
        pq.actions = Set.of(Actions.INV_ADD);
        pq.worlds = List.of(r.world());
        pq.x = r.x(); pq.y = r.y(); pq.z = r.z();
        pq.radius = plugin.settings().reconcileRadius();
        pq.since = r.time();
        pq.until = until;
        Map<String, List<PutRef>> putsBy = new HashMap<>();
        List<Pickup> list = new ArrayList<>();
        for (LogRow p : db.selectRows(pq, true, 0, 0, "meta = ? AND rolled_back = 0", List.<Object>of("pickup"))) {
            if (p.oldExtra() == null || p.uuid() == null || !used.add(p.id())) continue;
            List<PutRef> puts = putsBy.get(p.uuid());
            if (puts == null) {
                puts = new ArrayList<>();
                Query cq = new Query();
                cq.type = "container";
                cq.actions = Set.of(Actions.PUT);
                cq.since = r.time();
                cq.until = until;
                for (LogRow c : db.selectRows(cq, true, 0, 0, "uuid = ? AND rolled_back = 0", List.<Object>of(p.uuid()))) {
                    if (c.oldExtra() == null || planned.contains(c.id())) continue;
                    PutRef ref = putCache.get(c.id());
                    if (ref == null) {
                        ref = new PutRef(c.id(), c.world(), c.x(), c.y(), c.z(), c.oldExtra(), new int[]{Math.max(1, c.amount())});
                        putCache.put(c.id(), ref);
                    }
                    puts.add(ref);
                }
                putsBy.put(p.uuid(), puts);
            }
            list.add(new Pickup(p.id(), p.uuid(), p.player(), p.oldExtra(), Math.max(1, p.amount()), puts));
        }
        return new ReconOp(r.world(), r.x(), r.y(), r.z(), data, extra, list);
    }

    private void step(Op op, Stats st, boolean dry, Plan inverse) {
        switch (op) {
            case BlockOp b -> stepBlock(b, st, dry, inverse);
            case ContainerOp c -> stepContainer(c, st, dry, inverse);
            case EntityOp e -> stepEntity(e, st, dry, inverse);
            case InvOp i -> stepInventory(i, st, dry, inverse);
            case ReconOp r -> stepRecon(r, st, dry, inverse);
        }
    }

    private void stepBlock(BlockOp op, Stats st, boolean dry, Plan inverse) {
        ServerLevel w = Mc.level(op.world());
        if (w == null) { st.worldMissing++; return; }
        st.blockTotal++;
        try {
            if (op.frame()) { stepFrame(op, w, st, dry, inverse); return; }
            BlockPos pos = new BlockPos(op.x(), op.y(), op.z());
            BlockState cur = w.getBlockState(pos);
            BlockState target = Mc.parseState(op.data());
            if (cur.equals(target) && op.extra() == null) { st.blockUnchanged++; return; }

            st.blockChanged++;
            st.transitions.merge(Msg.cleanBlock(Mc.stateString(cur)) + " → " + Msg.cleanBlock(op.data()), 1, Integer::sum);
            st.perWorld.merge(op.world(), 1, Integer::sum);
            st.bounds(op.x(), op.y(), op.z());
            if (dry) {
                if (st.previewBlocks.size() < plugin.settings().previewMaxBlocks()) {
                    st.previewBlocks.add(new Object[]{op.world(), op.x(), op.y(), op.z(), target});
                }
                return;
            }
            inverse.blocks.computeIfAbsent(op.world(), k -> new LinkedHashMap<>()).put(new Pos(op.x(), op.y(), op.z()),
                    new BlockOp(op.world(), op.x(), op.y(), op.z(), Mc.stateString(cur), Mc.captureBlockEntity(w, pos), null));
            Hooks.suppress(() -> {
                Mc.setState(w, pos, target);
                if (op.extra() != null) Mc.restoreBlockEntity(w, pos, target, op.extra());
            });
        } catch (Exception ex) {
            st.blockFailed++;
            if (!dry) plugin.getLogger().warning("Could not apply block at " + op.x() + "," + op.y() + "," + op.z() + ": " + ex.getMessage());
        }
    }

    private void stepFrame(BlockOp op, ServerLevel w, Stats st, boolean dry, Plan inverse) {
        String wantFacing = FrameState.facingOf(op.data()) != null ? FrameState.facingOf(op.data()) : op.facing();
        ItemFrame existing = FrameState.find(w, op.x(), op.y(), op.z(), wantFacing);
        String cur = existing == null ? AIR : FrameState.encode(existing);
        if (cur.equals(op.data())) { st.blockUnchanged++; return; }
        st.blockChanged++;
        st.frames++;
        st.transitions.merge((FrameState.isFrame(cur) ? FrameState.describe(cur) : "air") + " → "
                + (FrameState.isFrame(op.data()) ? FrameState.describe(op.data()) : "air"), 1, Integer::sum);
        st.perWorld.merge(op.world(), 1, Integer::sum);
        st.bounds(op.x(), op.y(), op.z());
        if (dry) return;
        inverse.blocks.computeIfAbsent(op.world(), k -> new LinkedHashMap<>()).put(new Pos(op.x(), op.y(), op.z()),
                new BlockOp(op.world(), op.x(), op.y(), op.z(), cur, null, existing == null ? wantFacing : FrameState.facingOf(cur)));
        Hooks.suppress(() -> FrameState.apply(w, op.x(), op.y(), op.z(), op.data(), wantFacing));
    }

    private void stepContainer(ContainerOp op, Stats st, boolean dry, Plan inverse) {
        ServerLevel w = Mc.level(op.world());
        List<ItemStack> stacks = w == null ? List.of() : ItemSerializer.expand(op.item(), op.amount());
        if (stacks.isEmpty()) { st.containerSkipped++; return; }
        BlockPos pos = new BlockPos(op.x(), op.y(), op.z());
        Container c = Mc.containerAt(w, pos);
        if (c == null) { st.containerSkipped++; return; }
        String mat = Mc.itemPath(stacks.get(0));
        st.containerItems.computeIfAbsent(mat, k -> new int[2])[op.add() ? 0 : 1] += op.amount();
        st.containerSpots.add(op.world() + ":" + op.x() + ":" + op.y() + ":" + op.z());
        if (dry) return;
        for (ItemStack stack : stacks) {
            if (op.add()) {
                ItemStack rest = Mc.insert(c, stack, c.getContainerSize());
                Mc.dropItem(w, op.x() + 0.5, op.y() + 1.0, op.z() + 0.5, rest);
            } else {
                Mc.extract(c, stack, c.getContainerSize());
            }
        }
        st.containerDone++;
        inverse.containers.add(new ContainerOp(op.world(), op.x(), op.y(), op.z(), op.item(), op.amount(), !op.add()));
    }

    private void stepEntity(EntityOp op, Stats st, boolean dry, Plan inverse) {
        ServerLevel w = Mc.level(op.world());
        if (w == null) { st.entitySkipped++; return; }
        int[] counts = st.entityTypes.computeIfAbsent(op.type(), k -> new int[2]);
        if (op.spawn()) {
            if (op.data() == null) { st.entitySkipped++; return; }
            counts[0]++;
            if (dry) return;
            Entity[] holder = new Entity[1];
            Hooks.suppress(() -> holder[0] = Mc.respawn(w, op.data()));
            Entity e = holder[0];
            if (e == null) { counts[0]--; st.entitySkipped++; return; }
            inverse.entities.add(new EntityOp(op.world(), op.x(), op.y(), op.z(), false, op.type(), null, e.getUUID().toString()));
        } else {
            if (op.uuid() == null) { st.entitySkipped++; return; }
            try {
                Entity e = Mc.findEntity(UUID.fromString(op.uuid()));
                if (e == null || e instanceof ServerPlayer) { st.entitySkipped++; return; }
                counts[1]++;
                if (dry) return;
                String ser = Mc.serializeEntity(e);
                e.discard();
                if (ser != null) inverse.entities.add(new EntityOp(op.world(), op.x(), op.y(), op.z(), true, op.type(), ser, null));
            } catch (IllegalArgumentException ex) {
                st.entitySkipped++;
            }
        }
    }

    private void stepInventory(InvOp op, Stats st, boolean dry, Plan inverse) {
        ServerPlayer p;
        try { p = Mc.player(UUID.fromString(op.uuid())); } catch (IllegalArgumentException ex) { p = null; }
        List<ItemStack> stacks = p == null ? List.of() : ItemSerializer.expand(op.item(), op.amount());
        if (stacks.isEmpty()) { st.invOffline++; return; }
        st.invPlayers.computeIfAbsent(Mc.name(p), k -> new int[2])[op.add() ? 0 : 1] += op.amount();
        if (dry) return;
        for (ItemStack stack : stacks) {
            if (op.add()) {
                ItemStack rest = Mc.insert(p.getInventory(), stack, 36);
                Mc.dropItem(p.level(), p.getX(), p.getY(), p.getZ(), rest);
            } else {
                Mc.extract(p.getInventory(), stack, 36);
            }
        }
        st.invDone++;
        inverse.inventories.add(new InvOp(op.uuid(), op.player(), op.item(), op.amount(), !op.add()));
    }

    private int moveBack(List<PutRef> puts, ItemStack proto, int need, Stats st, Plan inverse) {
        int got = 0;
        for (PutRef pr : puts) {
            if (need <= 0) break;
            if (pr.left()[0] <= 0) continue;
            ItemStack pp = ItemSerializer.decode(pr.item());
            if (pp == null || !same(pp, proto)) continue;
            int n2 = Math.min(need, pr.left()[0]);
            int before = st.containerDone;
            stepContainer(new ContainerOp(pr.world(), pr.x(), pr.y(), pr.z(), pr.item(), n2, false), st, false, inverse);
            if (st.containerDone > before) {
                pr.left()[0] -= n2;
                need -= n2;
                got += n2;
                st.reconMoved += n2;
            }
        }
        return got;
    }

    private void stepRecon(ReconOp op, Stats st, boolean dry, Plan inverse) {
        if (dry) return;
        ServerLevel w = Mc.level(op.world());
        if (w == null) return;
        List<ItemStack> want = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack s : Mc.contentsOf(w, new BlockPos(op.x(), op.y(), op.z()), op.data(), op.extra())) {
            int i = 0;
            while (i < want.size() && !same(want.get(i), s)) i++;
            if (i == want.size()) { want.add(s.copyWithCount(1)); counts.add(s.getCount()); }
            else counts.set(i, counts.get(i) + s.getCount());
        }
        if (want.isEmpty()) return;
        int radius = plugin.settings().reconcileRadius();
        AABB box = new AABB(op.x() - radius, op.y() - radius, op.z() - radius, op.x() + radius + 1.0, op.y() + radius + 1.0, op.z() + radius + 1.0);
        for (ItemEntity e : w.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = e.getItem();
            for (int i = 0; i < want.size(); i++) {
                if (counts.get(i) <= 0 || !same(want.get(i), stack)) continue;
                int take = Math.min(counts.get(i), stack.getCount());
                counts.set(i, counts.get(i) - take);
                st.reconGround += take;
                if (take >= stack.getCount()) e.discard();
                else e.setItem(stack.copyWithCount(stack.getCount() - take));
                break;
            }
        }
        for (Pickup p : op.pickups()) {
            ItemStack proto = ItemSerializer.decode(p.item());
            if (proto == null) continue;
            int i = 0;
            while (i < want.size() && !same(want.get(i), proto)) i++;
            if (i == want.size() || counts.get(i) <= 0) continue;
            int n = Math.min(counts.get(i), p.amount());
            counts.set(i, counts.get(i) - n);
            ServerPlayer pl;
            try { pl = Mc.player(UUID.fromString(p.uuid())); } catch (IllegalArgumentException ex) { pl = null; }
            int need = n;
            if (pl != null) {
                int removed = takeFrom(pl, proto, n);
                if (removed > 0) {
                    st.reconInv += removed;
                    inverse.inventories.add(new InvOp(p.uuid(), p.player(), p.item(), removed, true));
                }
                need = n - removed;
                if (need > 0) need -= moveBack(p.puts(), proto, need, st, inverse);
                if (need > 0) st.reconMissed += need;
            } else {
                need -= moveBack(p.puts(), proto, need, st, inverse);
                if (need > 0) { reclaim().add(p.uuid(), p.player(), p.item(), need); st.reconQueued += need; }
            }
        }
    }

    private interface Done { void finish(Stats st, Plan inverse); }

    private void drive(Plan plan, boolean dry, Done done) { drive(plan, dry, -1L, 0, done); }

    private void drive(Plan plan, boolean dry, long jid, int resumeFrom, Done done) {
        List<Op> ops = plan.flatten();
        int blockCount = 0;
        for (Map<Pos, BlockOp> m : plan.blocks.values()) blockCount += m.size();
        final int prefixEnd = plan.prefix();
        final int blocksEnd = blockCount + prefixEnd;
        Stats st = new Stats();
        Plan inverse = dry ? null : new Plan(!plan.restore, plan.query);
        long budget = plugin.settings().rollbackBudgetMs() * 1_000_000L;
        long started = System.currentTimeMillis();
        Sched.Handle[] self = new Sched.Handle[1];
        int[] index = {resumeFrom > 0 ? (blocksEnd == prefixEnd ? resumeFrom : prefixEnd) : 0};
        int[] tick = {0};
        self[0] = Sched.timer(1L, 1L, () -> {
            long end = System.nanoTime() + budget;
            while (index[0] < ops.size() && System.nanoTime() < end) {
                try { step(ops.get(index[0]), st, dry, inverse); }
                catch (Exception ex) { plugin.getLogger().warning("Rollback step failed: " + ex.getMessage()); }
                index[0]++;
                if (index[0] == blocksEnd && resumeFrom > blocksEnd) index[0] = resumeFrom;
            }
            if (jid >= 0 && ++tick[0] % 10 == 0) plugin.journal().progress(jid, index[0]);
            if (index[0] >= ops.size()) {
                self[0].cancel();
                st.millis = System.currentTimeMillis() - started;
                done.finish(st, inverse);
            }
        });
    }

    private void runReal(Sender sender, Plan plan, String label, boolean storeUndo) {
        boolean silent = plan.query != null && plan.query.hasTag("silent");
        if (!silent && plan.size() > 5000) Msg.info(sender, label + " started: " + plan.size() + " changes. This may take a moment...");
        long jid = plugin.journal().begin(sender.name(), plan.restore, plan.query, plan.size());
        drive(plan, false, jid, 0, (st, inverse) -> {
            if (storeUndo && inverse.size() > 0) undo.put(sender.key(), new UndoState(inverse, plan.rowIds, plan.restore));
            db.markRolledBack(plan.rowIds, !plan.restore);
            plugin.journal().finish(jid);
            report(sender, label, plan, st, false);
        });
    }

    private void runUndo(Sender sender, UndoState u) {
        Plan inv = u.inverse();
        drive(inv, false, (st, ignored) -> {
            db.markRolledBack(u.flagIds(), !u.flagValue());
            report(sender, "Undo", inv, st, false);
        });
    }

    private void runDry(Sender sender, Plan plan) {
        drive(plan, true, (st, ignored) -> {
            UUID key = sender.key();
            Pending old = pending.remove(key);
            if (old != null) { old.expiry().cancel(); revertPreview(sender, old.shown()); }

            List<Object[]> shown = new ArrayList<>();
            ServerPlayer player = sender.player();
            if (player != null) {
                for (Object[] pb : st.previewBlocks) {
                    if (Mc.level((String) pb[0]) == null) continue;
                    player.connection.send(new ClientboundBlockUpdatePacket(new BlockPos((int) pb[1], (int) pb[2], (int) pb[3]), (BlockState) pb[4]));
                    shown.add(new Object[]{pb[0], pb[1], pb[2], pb[3]});
                }
            }
            Sched.Handle expiry = Sched.later(plugin.settings().previewTimeoutMs() / 50L, () -> {
                Pending p = pending.remove(key);
                if (p != null) { revertPreview(sender, p.shown()); Msg.info(sender, "Preview expired."); }
            });
            pending.put(key, new Pending(plan, shown, expiry));
            printPreview(sender, plan, st, shown.size());
        });
    }

    private void revertPreview(Sender sender, List<Object[]> shown) {
        ServerPlayer player = sender.player();
        if (player == null || shown.isEmpty()) return;
        for (Object[] s : shown) {
            ServerLevel w = Mc.level((String) s[0]);
            if (w == null) continue;
            BlockPos pos = new BlockPos((int) s[1], (int) s[2], (int) s[3]);
            if (!w.hasChunkAt(pos)) continue;
            player.connection.send(new ClientboundBlockUpdatePacket(pos, w.getBlockState(pos)));
        }
    }

    private static String describeScope(Query q) {
        List<String> parts = new ArrayList<>();
        parts.add("users=" + (q.users.isEmpty() ? "all" : String.join(",", q.users)));
        parts.add("time=" + (q.since > 0 ? Msg.ago(System.currentTimeMillis() - q.since) + " back" : "all"));
        if (q.radius >= 0) parts.add("radius=" + q.radius + " @ " + q.x + "/" + q.y + "/" + q.z);
        parts.add("world=" + (q.worlds.isEmpty() ? "all" : String.join(",", q.worlds)));
        parts.add("actions=" + (q.rawAction == null ? "all" : q.rawAction));
        if (!q.include.isEmpty()) parts.add("include=" + String.join(",", q.include));
        if (!q.exclude.isEmpty()) parts.add("exclude=" + String.join(",", q.exclude));
        return String.join(", ", parts);
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.removeIf(e -> e.getValue() <= 0);
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return l.size() > n ? l.subList(0, n) : l;
    }

    private void printPreview(Sender to, Plan plan, Stats st, int shownBlocks) {
        List<String> L = new ArrayList<>();
        String pageTitle = "";
        String pageExtra = null;
        Query q = plan.query;
        boolean verbose = q != null && q.hasTag("verbose");
        boolean silent = q != null && q.hasTag("silent");
        String what = plan.restore ? "Restore" : "Rollback";
        if (silent) {
            Msg.send(to, "<green>" + what + " preview:</green> <white>" + st.blockChanged + "</white> blocks, <white>" + st.containerSpots.size()
                    + "</white> containers, <white>" + st.entityTypes.size() + "</white> entity types. "
                    + "<click:run_command:'/up apply'><green>[Apply]</green></click> <click:run_command:'/up cancel'><red>[Cancel]</red></click>");
            return;
        }
        pageTitle = what + " Preview";
        L.add(" <gray>Nothing has been changed yet. This is what would happen:</gray>");
        L.add("");
        if (q != null) L.add(Pager.kv("Scope", Msg.esc(describeScope(q))));
        L.add(Pager.kv("Log entries", plan.rows + " <gray>(blocks " + plan.blockRows + ", containers " + plan.containerRows
                + ", entities " + plan.entityRows + ", inventories " + plan.inventoryRows + ")</gray>"));

        int topN = verbose ? PREVIEW_TOP_VERBOSE : PREVIEW_TOP;
        if (st.blockTotal > 0 || st.blockFailed > 0) {
            L.add("");
            L.add(" <green><bold>Blocks</bold></green> <gray>(" + st.blockTotal + " positions)</gray>");
            L.add("   <white>" + st.blockChanged + "</white> <gray>would change</gray><dark_gray>,</dark_gray> <white>" + st.blockUnchanged
                    + "</white> <gray>already correct</gray>" + (st.frames > 0 ? "<dark_gray>,</dark_gray> <white>" + st.frames + "</white> <gray>item frames</gray>" : "")
                    + (st.blockFailed > 0 ? "<dark_gray>,</dark_gray> <red>" + st.blockFailed + " invalid</red>" : ""));
            for (Map.Entry<String, Integer> e : top(st.transitions, topN)) {
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> <green>×" + e.getValue() + "</green>");
            }
            if (st.transitions.size() > topN) L.add("   <dark_gray>… and " + (st.transitions.size() - topN) + " more kinds</dark_gray>");
            if (st.min != null) {
                L.add("   <gray>Area:</gray> <white>" + st.min[0] + " " + st.min[1] + " " + st.min[2] + "</white> <gray>to</gray> <white>"
                        + st.max[0] + " " + st.max[1] + " " + st.max[2] + "</white>");
            }
            if (verbose) for (Map.Entry<String, Integer> e : st.perWorld.entrySet()) {
                L.add("   <gray>World</gray> <white>" + Msg.esc(e.getKey()) + "</white><dark_gray>:</dark_gray> <green>" + e.getValue() + "</green>");
            }
            if (shownBlocks > 0) L.add("   <gray>Showing <white>" + shownBlocks + "</white> blocks client-side for " + plugin.settings().previewTimeoutMs() / 1000 + "s.</gray>");
        }
        if (!st.containerItems.isEmpty()) {
            L.add("");
            L.add(" <green><bold>Containers</bold></green> <gray>(" + st.containerSpots.size() + " containers)</gray>");
            int shown = 0;
            for (Map.Entry<String, int[]> e : st.containerItems.entrySet()) {
                if (shown++ >= topN) { L.add("   <dark_gray>… and " + (st.containerItems.size() - topN) + " more item types</dark_gray>"); break; }
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> "
                        + (e.getValue()[0] > 0 ? "<green>+" + e.getValue()[0] + "</green> " : "")
                        + (e.getValue()[1] > 0 ? "<red>-" + e.getValue()[1] + "</red>" : ""));
            }
            if (st.containerSkipped > 0) L.add("   <gray>Skipped (container missing/unloaded):</gray> <white>" + st.containerSkipped + "</white>");
        }
        if (!st.entityTypes.isEmpty()) {
            L.add("");
            L.add(" <green><bold>Entities</bold></green>");
            for (Map.Entry<String, int[]> e : st.entityTypes.entrySet()) {
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> "
                        + (e.getValue()[0] > 0 ? "<green>respawn ×" + e.getValue()[0] + "</green> " : "")
                        + (e.getValue()[1] > 0 ? "<red>remove ×" + e.getValue()[1] + "</red>" : ""));
            }
            if (st.entitySkipped > 0) L.add("   <gray>Skipped (not found/unloaded):</gray> <white>" + st.entitySkipped + "</white>");
        }
        if (!st.invPlayers.isEmpty() || st.invOffline > 0) {
            L.add("");
            L.add(" <green><bold>Inventories</bold></green>");
            for (Map.Entry<String, int[]> e : st.invPlayers.entrySet()) {
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> "
                        + (e.getValue()[0] > 0 ? "<green>+" + e.getValue()[0] + " items</green> " : "")
                        + (e.getValue()[1] > 0 ? "<red>-" + e.getValue()[1] + " items</red>" : ""));
            }
            if (st.invOffline > 0) L.add("   <gray>Skipped (player offline):</gray> <white>" + st.invOffline + "</white>");
        }
        if (st.worldMissing > 0) L.add(" <gray>Skipped (world not loaded):</gray> <white>" + st.worldMissing + "</white>");
        L.add("");
        pageExtra = " <click:run_command:'/up apply'><hover:show_text:'Perform this " + what.toLowerCase(Locale.ROOT) + "'><green><bold>[Apply]</bold></green></hover></click>   "
                + "<click:run_command:'/up cancel'><hover:show_text:'Discard the preview'><red><bold>[Cancel]</bold></red></hover></click>"
                + "   <gray>or /up apply, /up cancel</gray>";
        sendPaged(to, pageTitle, L, pageExtra);
    }

    private void report(Sender to, String label, Plan plan, Stats st, boolean preview) {
        List<String> L = new ArrayList<>();
        String pageTitle = "";
        String pageExtra = null;
        Query q = plan.query;
        boolean silent = q != null && q.hasTag("silent");
        boolean verbose = q != null && q.hasTag("verbose");
        double secs = st.millis / 1000.0;
        int total = st.blockChanged + st.containerDone + st.invDone;
        for (int[] c : st.entityTypes.values()) total += c[0] + c[1];
        if (silent) {
            Msg.ok(to, label + " complete: <white>" + total + "</white> changes in " + String.format(Locale.ROOT, "%.2fs", secs) + ".");
            return;
        }
        pageTitle = label + " complete";
        if (st.blockTotal > 0) {
            L.add(Pager.kv("Blocks", st.blockChanged + " changed <gray>(" + st.blockUnchanged + " already correct"
                    + (st.blockFailed > 0 ? ", " + st.blockFailed + " failed" : "") + ")</gray>"));
        }
        if (st.containerDone + st.containerSkipped > 0) {
            L.add(Pager.kv("Containers", st.containerDone + " transactions <gray>(" + st.containerSkipped + " skipped)</gray>"));
        }
        if (!st.entityTypes.isEmpty()) {
            int respawn = 0, removed = 0;
            for (int[] c : st.entityTypes.values()) { respawn += c[0]; removed += c[1]; }
            L.add(Pager.kv("Entities", respawn + " restored, " + removed + " removed <gray>(" + st.entitySkipped + " skipped)</gray>"));
        }
        if (st.reconGround + st.reconInv + st.reconMoved + st.reconQueued + st.reconMissed > 0) {
            L.add(Pager.kv("Duplicates prevented", st.reconGround + " dropped items removed, " + st.reconInv + " taken from players, "
                    + st.reconMoved + " taken from other containers <gray>(" + st.reconQueued + " queued for offline players, "
                    + st.reconMissed + " not found)</gray>"));
        }
        if (st.invDone + st.invOffline > 0) {
            L.add(Pager.kv("Inventories", st.invDone + " changes <gray>(" + st.invOffline + " skipped, player offline)</gray>"));
        }
        if (st.worldMissing > 0) L.add(Pager.kv("Skipped", st.worldMissing + " <gray>(world not loaded)</gray>"));
        if (verbose) {
            for (Map.Entry<String, Integer> e : top(st.transitions, PREVIEW_TOP_VERBOSE)) {
                L.add("   <dark_gray>•</dark_gray> <white>" + Msg.esc(e.getKey()) + "</white> <green>×" + e.getValue() + "</green>");
            }
        }
        L.add(Pager.kv("Time", String.format(Locale.ROOT, "%.2fs", secs)));
        if (!"Undo".equals(label) && !label.startsWith("Recovered")) pageExtra = " <gray>Revert with</gray> <click:run_command:'/up undo'><green>/up undo</green></click>";
        sendPaged(to, pageTitle, L, pageExtra);
    }

    public void resumePending() {
        db.submit(() -> {
            List<RollbackJournal.Entry> list;
            try { list = plugin.journal().pending(); } catch (Exception e) { return; }
            for (RollbackJournal.Entry en : list) {
                try {
                    Query q = en.query();
                    q.until = en.started();
                    Set<String> tags = new LinkedHashSet<>(q.tags);
                    tags.remove("silent");
                    tags.remove("preview");
                    q.tags = tags;
                    plugin.getLogger().warning("[Recovery] Unfinished " + (en.restore() ? "restore" : "rollback") + " by " + en.actor() + " found - resuming.");
                    Plan plan = buildPlan(q, en.restore());
                    if (plan.rows == 0 || plan.size() == 0) { plugin.journal().finish(en.id()); continue; }
                    int from = en.done();
                    Sched.main(() -> {
                        Sender console = Sender.console();
                        drive(plan, false, en.id(), from, (st, inverse) -> {
                            db.markRolledBack(plan.rowIds, !plan.restore);
                            plugin.journal().finish(en.id());
                            report(console, "Recovered " + (en.restore() ? "restore" : "rollback"), plan, st, false);
                        });
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("[Recovery] Could not resume: " + e.getMessage());
                }
            }
        });
    }

    private static void sendPaged(Sender to, String title, List<String> lines, String extra) {
        Investigator.Out out = new Investigator.Out() {
            @Override public void raw(String mini) { Msg.raw(to, mini); }
            @Override public void err(String text) { Msg.err(to, Msg.esc(text)); }
            @Override public String key() { return to.key().toString(); }
        };
        Pager.send(out, title, List.of(), lines, 1, extra);
    }

    public void startSilent(Query q, boolean restore) {
        Set<String> tags = new LinkedHashSet<>(q.tags);
        tags.add("silent");
        Query c = q.copy();
        c.tags = tags;
        start(Sender.console(), c, restore);
    }
}
