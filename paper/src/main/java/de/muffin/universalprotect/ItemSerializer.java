package de.muffin.universalprotect;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class ItemSerializer {
    private ItemSerializer() {}

    public static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty() || b64.equals("-")) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            return null;
        }
    }

    public static String encodeProto(ItemStack stack) {
        ItemStack c = stack.clone();
        c.setAmount(1);
        return encode(c);
    }

    public static List<ItemStack> expand(String protoB64, int amount) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack proto = decode(protoB64);
        if (proto == null) return out;
        int max = Math.max(1, proto.getMaxStackSize());
        int left = Math.max(1, amount);
        while (left > 0) {
            ItemStack c = proto.clone();
            int n = Math.min(max, left);
            c.setAmount(n);
            out.add(c);
            left -= n;
        }
        return out;
    }

    public static String text(ItemStack s) {
        return s.getType().name().toLowerCase(Locale.ROOT) + " x" + s.getAmount();
    }

    public static String materialOf(String text) {
        if (text == null) return "?";
        int sp = text.indexOf(' ');
        return sp > 0 ? text.substring(0, sp) : text;
    }
}
