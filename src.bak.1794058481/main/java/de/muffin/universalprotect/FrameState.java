package de.muffin.universalprotect;

import org.bukkit.Location;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Zustand eines Item-Frames als "Pseudo-Blockdaten". Änderungen an Rahmen (Item einsetzen, drehen, entfernen)
 * werden dadurch wie Blockänderungen geloggt (-block alter Zustand, +block neuer Zustand) und lassen sich zurückrollen.
 * <p>Format: {@code frame:<typ>:<richtung>:<rotation>:<sichtbar>:<itemLabel>:<itemBase64>}</p>
 */
public final class FrameState {
    public static final String PREFIX = "frame:";

    public record State(String type, String facing, String rotation, boolean visible, String label, String itemB64) {}

    private FrameState() {}

    public static boolean isFrame(String data) { return data != null && data.startsWith(PREFIX); }

    public static String encode(ItemFrame f) {
        ItemStack it = f.getItem();
        boolean empty = it.getType().isAir();
        String type = f instanceof GlowItemFrame ? "glow_item_frame" : "item_frame";
        return PREFIX + type + ":" + f.getFacing().name() + ":" + f.getRotation().name() + ":" + (f.isVisible() ? 1 : 0)
                + ":" + (empty ? "-" : it.getType().name().toLowerCase(Locale.ROOT))
                + ":" + (empty ? "-" : ItemSerializer.encode(it));
    }

    public static State parse(String data) {
        if (!isFrame(data)) return null;
        String[] p = data.split(":", 7);
        if (p.length < 7) return null;
        return new State(p[1], p[2], p[3], "1".equals(p[4]), p[5], p[6]);
    }

    /** Lesbare Kurzform für Lookups, z.B. "item_frame (diamond)". */
    public static String describe(String data) {
        State s = parse(data);
        if (s == null) return "item_frame";
        return s.type() + (s.label().equals("-") ? " (empty)" : " (" + s.label() + ")") ;
    }

    public static ItemFrame find(World world, int x, int y, int z, String facing) {
        Location c = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        for (Entity e : world.getNearbyEntities(c, 0.6, 0.6, 0.6)) {
            if (!(e instanceof ItemFrame f)) continue;
            Location l = f.getLocation();
            if (l.getBlockX() != x || l.getBlockY() != y || l.getBlockZ() != z) continue;
            if (facing == null || f.getFacing().name().equals(facing)) return f;
        }
        return null;
    }

    /**
     * Stellt den Zielzustand am Block wieder her (Hauptthread).
     *
     * @param target Pseudo-Blockdaten oder "minecraft:air" (Rahmen entfernen)
     * @param prevFacing Blickrichtung des Rahmens, der entfernt werden soll (aus dem Gegenzustand), kann null sein
     * @return true, wenn etwas geändert wurde
     */
    public static boolean apply(World world, int x, int y, int z, String target, String prevFacing) {
        State st = parse(target);
        if (st == null) { // Ziel ist Luft -> Rahmen entfernen
            ItemFrame f = find(world, x, y, z, prevFacing);
            if (f == null) return false;
            f.remove();
            return true;
        }
        ItemFrame f = find(world, x, y, z, st.facing());
        if (f == null) {
            BlockFace face = BlockFace.valueOf(st.facing());
            Class<? extends ItemFrame> cls = st.type().equals("glow_item_frame") ? GlowItemFrame.class : ItemFrame.class;
            f = world.spawn(new Location(world, x, y, z), cls, spawned -> spawned.setFacingDirection(face, true));
        }
        ItemStack item = ItemSerializer.decode(st.itemB64());
        f.setItem(item == null ? new ItemStack(org.bukkit.Material.AIR) : item);
        try { f.setRotation(Rotation.valueOf(st.rotation())); } catch (IllegalArgumentException ignored) { /* alte Version */ }
        f.setVisible(st.visible());
        return true;
    }

    public static String facingOf(String data) {
        State s = parse(data);
        return s == null ? null : s.facing();
    }
}
