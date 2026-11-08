package de.muffin.universalprotect;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Hilfen für das Speichern von Items (SNBT über die Item-Codecs, Menge separat). */
public final class ItemSerializer {
    private ItemSerializer() {}

    /** Stack mit Menge 1 als SNBT (die Menge steht separat in der Datenbank). */
    public static String encodeProto(ItemStack stack) { return Mc.encodeItemProto(stack); }

    public static ItemStack decode(String snbt) { return Mc.decodeItem(snbt); }

    /** Erzeugt aus einem Prototyp (Menge 1) und einer Gesamtmenge normale Stacks (je höchstens Stackgröße). */
    public static List<ItemStack> expand(String proto, int amount) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack p = decode(proto);
        if (p == null) return out;
        int max = Math.max(1, p.getMaxStackSize());
        int left = Math.max(1, amount);
        while (left > 0) {
            int n = Math.min(max, left);
            out.add(p.copyWithCount(n));
            left -= n;
        }
        return out;
    }

    /** "diamond x3" */
    public static String text(ItemStack s, int amount) { return Mc.itemPath(s) + " x" + amount; }

    /** Item-Name aus einem Text wie "diamond x3". */
    public static String materialOf(String text) {
        if (text == null) return "?";
        int sp = text.indexOf(' ');
        return sp > 0 ? text.substring(0, sp) : text;
    }
}
