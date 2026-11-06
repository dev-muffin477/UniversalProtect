package de.muffin.universalprotect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.block.*;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BlockExtras {
    private BlockExtras() {}

    public static String capture(BlockState state) {
        try {
            if (state instanceof Container container) {
                ItemStack[] items = container.getSnapshotInventory().getContents();
                boolean empty = true;
                for (ItemStack item : items) { if (item != null && !item.getType().isAir()) { empty = false; break; } }
                if (empty) return null;
                YamlConfiguration config = new YamlConfiguration();
                config.set("items", items);
                return "inv:" + compress(Base64.getEncoder().encodeToString(config.saveToString().getBytes(StandardCharsets.UTF_8)));
            }
            if (state instanceof Sign sign) {
                StringBuilder sb = new StringBuilder("sign:").append(sign.isWaxed());
                for (Side side : Side.values()) {
                    SignSide ss = sign.getSide(side);
                    sb.append(';').append(ss.getColor() == null ? "" : ss.getColor().name()).append(',').append(ss.isGlowingText());
                    List<Component> lines = ss.lines();
                    for (int i = 0; i < 4; i++) {
                        Component line = i < lines.size() ? lines.get(i) : Component.empty();
                        sb.append(',').append(Base64.getEncoder().encodeToString(GsonComponentSerializer.gson().serialize(line).getBytes(StandardCharsets.UTF_8)));
                    }
                }
                return compress(sb.toString());
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void apply(Block block, String extra) {
        if (extra == null || extra.isEmpty()) return;
        try {
            BlockState state = block.getState();
            if (extra.startsWith("inv:") && state instanceof Container container) {
                String decoded = decompress(extra.substring(4));
                String serializedStr = new String(Base64.getDecoder().decode(decoded), StandardCharsets.UTF_8);
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(serializedStr);
                List<?> list = config.getList("items");
                if (list != null) {
                    container.getSnapshotInventory().setContents(list.toArray(new ItemStack[0]));
                    state.update(true, false);
                }
                return;
            }
            String decExtra = decompress(extra);
            if (decExtra != null && decExtra.startsWith("sign:") && state instanceof Sign sign) {
                String[] parts = decExtra.substring(5).split(";", -1);
                sign.setWaxed(Boolean.parseBoolean(parts[0]));
                Side[] sides = Side.values();
                for (int i = 0; i < sides.length && i + 1 < parts.length; i++) {
                    String[] f = parts[i + 1].split(",", -1);
                    SignSide ss = sign.getSide(sides[i]);
                    if (!f[0].isEmpty()) ss.setColor(DyeColor.valueOf(f[0]));
                    ss.setGlowingText(Boolean.parseBoolean(f[1]));
                    for (int l = 0; l < 4 && l + 2 < f.length; l++) {
                        ss.line(l, GsonComponentSerializer.gson().deserialize(new String(Base64.getDecoder().decode(f[l + 2]), StandardCharsets.UTF_8)));
                    }
                }
                state.update(true, false);
            }
        } catch (Exception ignored) {}
    }

    private static String compress(String str) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(StandardCharsets.UTF_8)); gzip.close();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) { return str; }
    }

    private static String decompress(String str) {
        try {
            byte[] bytes = Base64.getDecoder().decode(str);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 BufferedReader br = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
                StringBuilder outStr = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) outStr.append(line);
                return outStr.toString();
            }
        } catch (Exception e) { return str; }
    }
}
