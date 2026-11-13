package de.muffin.universalprotect;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** (De-)Serialisierung von ItemStacks inklusive aller Metadaten (Base64 der Paper-Bytes). */
public final class ItemSerializer {
    private ItemSerializer() {}

    public static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    /** @return der ItemStack oder null, wenn die Daten ungültig sind */
    public static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty() || b64.equals("-")) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            return null;
        }
    }

    /** Speichert den Stack mit Menge 1 (die Menge steht separat in der Datenbank, so sind beliebig große Mengen möglich). */
    public static String encodeProto(ItemStack stack) {
        ItemStack c = stack.clone();
        c.setAmount(1);
        return encode(c);
    }

    /** Erzeugt aus einem Prototyp (Menge 1) und einer Gesamtmenge normale Stacks (je höchstens Stackgröße). */
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

    /** "diamond x3" */
    public static String text(ItemStack s) {
        return s.getType().name().toLowerCase(Locale.ROOT) + " x" + s.getAmount();
    }

    /** Material-Name aus einem Text wie "diamond x3". */
    public static String materialOf(String text) {
        if (text == null) return "?";
        int sp = text.indexOf(' ');
        return sp > 0 ? text.substring(0, sp) : text;
    }
}
