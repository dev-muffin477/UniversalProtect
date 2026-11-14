package de.muffin.universalprotect;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ItemSerializer {
    private ItemSerializer() {}

    public static String encodeProto(ItemStack stack) { return Mc.encodeItemProto(stack); }

    public static ItemStack decode(String snbt) { return Mc.decodeItem(snbt); }

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

    public static String text(ItemStack s, int amount) { return Mc.itemPath(s) + " x" + amount; }

    public static String materialOf(String text) {
        if (text == null) return "?";
        int sp = text.indexOf(' ');
        return sp > 0 ? text.substring(0, sp) : text;
    }
}
