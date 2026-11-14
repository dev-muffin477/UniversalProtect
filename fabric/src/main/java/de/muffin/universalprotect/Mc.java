package de.muffin.universalprotect;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.*;

public final class Mc {
    private Mc() {}

    private static volatile MinecraftServer server;

    public static void setServer(MinecraftServer s) { server = s; }
    public static MinecraftServer serverOrNull() { return server; }
    public static MinecraftServer server() { return server; }

    public static HolderLookup.Provider registries() { return server.registryAccess(); }

    public static String key(Level level) {
        String s = String.valueOf(level.dimension());
        int slash = s.lastIndexOf("/ ");
        int end = s.lastIndexOf(']');
        return slash >= 0 && end > slash ? s.substring(slash + 2, end).trim() : s;
    }

    public static String normalizeWorld(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "world", "overworld" -> "minecraft:overworld";
            case "world_nether", "nether", "the_nether" -> "minecraft:the_nether";
            case "world_the_end", "end", "the_end" -> "minecraft:the_end";
            default -> n.contains(":") ? n : "minecraft:" + n;
        };
    }

    public static ServerLevel level(String name) {
        if (server == null || name == null) return null;
        String want = normalizeWorld(name);
        for (ServerLevel l : server.getAllLevels()) if (key(l).equals(want)) return l;
        return null;
    }

    public static List<String> worldKeys() {
        List<String> out = new ArrayList<>();
        if (server != null) for (ServerLevel l : server.getAllLevels()) out.add(key(l));
        return out;
    }

    public static String blockId(Block b) { return BuiltInRegistries.BLOCK.getKey(b).toString(); }
    public static String itemId(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
    private static final Map<String, net.minecraft.world.item.Item> ITEM_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static net.minecraft.world.item.Item itemByPath(String path) {
        net.minecraft.world.item.Item cached = ITEM_CACHE.get(path);
        if (cached != null) return cached;
        for (net.minecraft.world.item.Item i : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(i).getPath().equals(path)) { ITEM_CACHE.put(path, i); return i; }
        }
        return null;
    }

    public static String itemPath(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }
    public static String entityPath(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); }
    public static String entityPath(EntityType<?> t) { return BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath(); }

    public static String stateString(BlockState s) { return BlockStateParser.serialize(s); }

    public static BlockState parseState(String s) throws CommandSyntaxException {
        return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
    }

    public static boolean isAir(BlockState s) { return s.isAir(); }

    public static boolean setState(ServerLevel level, BlockPos pos, BlockState state) {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    public static String captureBlockEntity(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return null;
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            return tag == null ? null : tag.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean restoreBlockEntity(Level level, BlockPos pos, BlockState state, String snbt) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            BlockEntity be = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
            if (be == null) return false;
            level.setBlockEntity(be);
            be.setChanged();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String encodeItemProto(ItemStack stack) {
        try {
            ItemStack one = stack.copyWithCount(1);
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries());
            Tag t = ItemStack.CODEC.encodeStart(ops, one).getOrThrow();
            return t.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    public static ItemStack decodeItem(String snbt) {
        if (snbt == null || snbt.isEmpty() || snbt.equals("-")) return null;
        try {
            Tag t = TagParser.parseCompoundFully(snbt);
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries());
            ItemStack s = ItemStack.CODEC.parse(ops, t).getOrThrow();
            return s.isEmpty() ? null : s;
        } catch (Throwable e) {
            return null;
        }
    }

    public static Container containerAt(Level level, BlockPos pos) {
        try {
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof ChestBlock cb) {
                Container c = ChestBlock.getContainer(cb, st, level, pos, true);
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    public static ItemStack insert(Container c, ItemStack stack, int slots) {
        ItemStack rest = stack.copy();
        int n = Math.min(c.getContainerSize(), slots);
        for (int i = 0; i < n && !rest.isEmpty(); i++) {
            ItemStack cur = c.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, rest)) {
                int space = cur.getMaxStackSize() - cur.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, rest.getCount());
                cur.grow(move);
                rest.shrink(move);
            }
        }
        for (int i = 0; i < n && !rest.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                int move = Math.min(rest.getMaxStackSize(), rest.getCount());
                c.setItem(i, rest.copyWithCount(move));
                rest.shrink(move);
            }
        }
        c.setChanged();
        return rest;
    }

    public static int extract(Container c, ItemStack stack, int slots) {
        int left = stack.getCount();
        int n = Math.min(c.getContainerSize(), slots);
        for (int i = 0; i < n && left > 0; i++) {
            ItemStack cur = c.getItem(i);
            if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack)) continue;
            int take = Math.min(left, cur.getCount());
            cur.shrink(take);
            left -= take;
            if (cur.isEmpty()) c.setItem(i, ItemStack.EMPTY);
        }
        c.setChanged();
        return stack.getCount() - left;
    }

    public static void dropItem(Level level, double x, double y, double z, ItemStack stack) {
        if (stack.isEmpty()) return;
        level.addFreshEntity(new ItemEntity(level, x, y, z, stack));
    }

    public static String serializeEntity(Entity e) {
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.level().registryAccess());
            e.saveWithoutId(out);
            CompoundTag tag = out.buildResult();
            tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            return tag.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Entity respawn(ServerLevel level, String snbt) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            String id = tag.getString("id").orElse("");
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.parse(id));
            if (type.isEmpty()) return null;
            Entity e = type.get().create(level, EntitySpawnReason.COMMAND);
            if (e == null) return null;
            e.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            return level.addFreshEntity(e) ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Entity findEntity(UUID id) {
        if (server == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    public static ServerPlayer player(UUID id) { return server == null ? null : server.getPlayerList().getPlayer(id); }

    public static ServerPlayer player(String name) { return server == null ? null : server.getPlayerList().getPlayerByName(name); }

    public static List<ServerPlayer> players() { return server == null ? List.of() : server.getPlayerList().getPlayers(); }

    public static String name(ServerPlayer p) { return p.getName().getString(); }
}
