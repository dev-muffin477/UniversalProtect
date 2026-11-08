package de.muffin.universalprotect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Zustand eines Item-Frames als "Pseudo-Blockdaten". Änderungen an Rahmen (Item einsetzen, drehen, entfernen)
 * werden dadurch wie Blockänderungen geloggt und lassen sich zurückrollen.
 * Format: {@code frame:<typ>:<richtung>:<rotation>:<sichtbar>:<itemLabel>:<itemSnbt>}
 */
public final class FrameState {
    public static final String PREFIX = "frame:";

    public record State(String type, String facing, int rotation, boolean visible, String label, String itemSnbt) {}

    private FrameState() {}

    public static boolean isFrame(String data) { return data != null && data.startsWith(PREFIX); }

    public static String encode(ItemFrame f) {
        ItemStack it = f.getItem();
        boolean empty = it.isEmpty();
        String type = f instanceof GlowItemFrame ? "glow_item_frame" : "item_frame";
        String snbt = empty ? null : Mc.encodeItemProto(it);
        return PREFIX + type + ":" + f.getDirection().getName() + ":" + f.getRotation() + ":" + (f.isInvisible() ? 0 : 1)
                + ":" + (empty ? "-" : Mc.itemPath(it)) + ":" + (snbt == null ? "-" : snbt);
    }

    public static State parse(String data) {
        if (!isFrame(data)) return null;
        String[] p = data.split(":", 7);
        if (p.length < 7) return null;
        int rot;
        try { rot = Integer.parseInt(p[3]); } catch (NumberFormatException e) { rot = 0; }
        return new State(p[1], p[2], rot, "1".equals(p[4]), p[5], p[6]);
    }

    public static String describe(String data) {
        State s = parse(data);
        if (s == null) return "item_frame";
        return s.type() + (s.label().equals("-") ? " (empty)" : " (" + s.label() + ")");
    }

    public static ItemFrame find(ServerLevel level, int x, int y, int z, String facing) {
        AABB box = new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0).inflate(0.1);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, box);
        for (ItemFrame f : frames) {
            BlockPos p = f.blockPosition();
            if (p.getX() != x || p.getY() != y || p.getZ() != z) continue;
            if (facing == null || f.getDirection().getName().equals(facing)) return f;
        }
        return null;
    }

    /** Stellt den Zielzustand wieder her (Hauptthread). */
    public static boolean apply(ServerLevel level, int x, int y, int z, String target, String prevFacing) {
        State st = parse(target);
        if (st == null) { // Ziel ist Luft -> Rahmen entfernen
            ItemFrame f = find(level, x, y, z, prevFacing);
            if (f == null) return false;
            f.discard();
            return true;
        }
        ItemFrame f = find(level, x, y, z, st.facing());
        if (f == null) {
            Direction dir = Direction.byName(st.facing());
            if (dir == null) return false;
            BlockPos pos = new BlockPos(x, y, z);
            f = st.type().equals("glow_item_frame") ? new GlowItemFrame(level, pos, dir) : new ItemFrame(level, pos, dir);
            level.addFreshEntity(f);
        }
        ItemStack item = ItemSerializer.decode(st.itemSnbt());
        f.setItem(item == null ? ItemStack.EMPTY : item);
        f.setRotation(st.rotation());
        f.setInvisible(!st.visible());
        return true;
    }

    public static String facingOf(String data) {
        State s = parse(data);
        return s == null ? null : s.facing();
    }
}
