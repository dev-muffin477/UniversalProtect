package de.muffin.universalprotect;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Kleiner MiniMessage-Ersatz (Teilmenge): Farben, bold/italic/underlined/strikethrough/obfuscated,
 * click:run_command|suggest_command|open_url und hover:show_text, Escape mit {@code \<}.
 */
public final class Mini {
    private Mini() {}

    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("dark_gray", 0x555555),
            Map.entry("blue", 0x5555FF), Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55),
            Map.entry("white", 0xFFFFFF));

    private record Frame(String name, Style style) {}

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }

    public static Component parse(String in) {
        MutableComponent root = Component.empty();
        Deque<Frame> stack = new ArrayDeque<>();
        Style cur = Style.EMPTY;
        StringBuilder text = new StringBuilder();
        int i = 0, n = in.length();
        while (i < n) {
            char c = in.charAt(i);
            if (c == '\\' && i + 1 < n && (in.charAt(i + 1) == '<' || in.charAt(i + 1) == '\\')) {
                text.append(in.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = findEnd(in, i);
                if (end > 0) {
                    String tag = in.substring(i + 1, end);
                    Style next = apply(tag, cur, stack);
                    if (next != null) {
                        flush(root, text, cur);
                        cur = next;
                        i = end + 1;
                        continue;
                    }
                }
            }
            text.append(c);
            i++;
        }
        flush(root, text, cur);
        return root;
    }

    private static int findEnd(String s, int from) {
        boolean quote = false;
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') quote = !quote;
            else if (c == '>' && !quote) return i;
        }
        return -1;
    }

    private static void flush(MutableComponent root, StringBuilder text, Style style) {
        if (text.length() == 0) return;
        root.append(Component.literal(text.toString()).withStyle(style));
        text.setLength(0);
    }

    private static List<String> split(String tag) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '\'') { quote = !quote; continue; }
            if (c == ':' && !quote) { out.add(sb.toString()); sb.setLength(0); continue; }
            sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    /** @return neuer Stil oder null, wenn das kein bekanntes Tag ist (dann wird es als Text ausgegeben) */
    private static Style apply(String tag, Style cur, Deque<Frame> stack) {
        if (tag.startsWith("/")) {
            String name = tag.substring(1).toLowerCase();
            if (stack.isEmpty()) return cur;
            if (name.isEmpty()) return stack.pop().style();
            for (Frame f : stack) {
                if (f.name().equals(name)) {
                    while (!stack.isEmpty()) {
                        Frame top = stack.pop();
                        if (top == f) return top.style();
                    }
                }
            }
            return cur;
        }
        List<String> parts = split(tag);
        String name = parts.get(0).toLowerCase();
        Style next;
        if (COLORS.containsKey(name)) {
            next = cur.withColor(TextColor.fromRgb(COLORS.get(name)));
        } else if (name.startsWith("#") && name.length() == 7) {
            try { next = cur.withColor(TextColor.fromRgb(Integer.parseInt(name.substring(1), 16))); }
            catch (NumberFormatException e) { return null; }
        } else {
            switch (name) {
                case "bold", "b" -> next = cur.withBold(true);
                case "italic", "i", "em" -> next = cur.withItalic(true);
                case "underlined", "u" -> next = cur.withUnderlined(true);
                case "strikethrough", "st" -> next = cur.withStrikethrough(true);
                case "obfuscated", "obf" -> next = cur.withObfuscated(true);
                case "click" -> {
                    if (parts.size() < 3) return null;
                    String val = String.join(":", parts.subList(2, parts.size()));
                    switch (parts.get(1).toLowerCase()) {
                        case "run_command" -> next = cur.withClickEvent(new ClickEvent.RunCommand(val));
                        case "suggest_command" -> next = cur.withClickEvent(new ClickEvent.SuggestCommand(val));
                        case "open_url" -> {
                            try { next = cur.withClickEvent(new ClickEvent.OpenUrl(URI.create(val))); }
                            catch (IllegalArgumentException e) { return null; }
                        }
                        default -> { return null; }
                    }
                }
                case "hover" -> {
                    if (parts.size() < 3 || !parts.get(1).equalsIgnoreCase("show_text")) return null;
                    String val = String.join(":", parts.subList(2, parts.size()));
                    next = cur.withHoverEvent(new HoverEvent.ShowText(parse(val)));
                }
                default -> { return null; }
            }
        }
        stack.push(new Frame(name, cur));
        return next;
    }
}
