package de.muffin.universalprotect;

import java.util.*;

public final class CommandRegions {
    public record Region(String label, int x1, int y1, int z1, int x2, int y2, int z2) {
        public long volume() {
            return (long) (x2 - x1 + 1) * (long) (y2 - y1 + 1) * (long) (z2 - z1 + 1);
        }
    }

    private static final Set<String> SHIFTING = Set.of("at", "positioned", "in", "align", "on");

    private static final class Ctx {
        final double ox, oy, oz;
        boolean relative;
        Ctx(double ox, double oy, double oz) { this.ox = ox; this.oy = oy; this.oz = oz; }

        int coord(String tok, int axis) {
            if (tok.startsWith("^")) throw new IllegalArgumentException("local");
            double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            if (tok.startsWith("~")) {
                relative = true;
                double off = tok.length() == 1 ? 0 : Double.parseDouble(tok.substring(1));
                return (int) Math.floor(origin + off);
            }
            if (tok.indexOf('.') >= 0) return (int) Math.floor(Double.parseDouble(tok));
            return Integer.parseInt(tok);
        }
    }

    private CommandRegions() {}

    private static String bare(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("minecraft:") ? l.substring(10) : l;
    }

    public static List<Region> parse(String raw, double ox, double oy, double oz) {
        try {
            return parse0(raw, ox, oy, oz);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<Region> parse0(String raw, double ox, double oy, double oz) {
        if (raw == null) return List.of();
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        String[] t = s.split("\\s+");
        if (t.length == 0) return List.of();
        int i = 0;
        boolean exec = false;
        if (bare(t[0]).equals("execute")) {
            exec = true;
            int run = -1;
            for (int j = 1; j < t.length; j++) {
                if (t[j].equalsIgnoreCase("run")) { run = j; break; }
                if (SHIFTING.contains(t[j].toLowerCase(Locale.ROOT))) return List.of();
            }
            if (run < 0 || run + 1 >= t.length) return List.of();
            i = run + 1;
        }
        String cmd = bare(t[i]);
        Ctx c = new Ctx(ox, oy, oz);
        List<Region> out = new ArrayList<>();
        switch (cmd) {
            case "setblock" -> {
                if (t.length < i + 5) return List.of();
                int x = c.coord(t[i + 1], 0), y = c.coord(t[i + 2], 1), z = c.coord(t[i + 3], 2);
                out.add(new Region("setblock", x, y, z, x, y, z));
            }
            case "fill" -> {
                if (t.length < i + 8) return List.of();
                out.add(box("fill", c.coord(t[i + 1], 0), c.coord(t[i + 2], 1), c.coord(t[i + 3], 2),
                        c.coord(t[i + 4], 0), c.coord(t[i + 5], 1), c.coord(t[i + 6], 2)));
            }
            case "clone" -> {
                if (t.length < i + 10) return List.of();
                if (t[i + 1].equalsIgnoreCase("from") || t[i + 1].equalsIgnoreCase("to")) return List.of();
                Region src = box("clone", c.coord(t[i + 1], 0), c.coord(t[i + 2], 1), c.coord(t[i + 3], 2),
                        c.coord(t[i + 4], 0), c.coord(t[i + 5], 1), c.coord(t[i + 6], 2));
                int dx = c.coord(t[i + 7], 0), dy = c.coord(t[i + 8], 1), dz = c.coord(t[i + 9], 2);
                out.add(new Region("clone", dx, dy, dz, dx + (src.x2() - src.x1()), dy + (src.y2() - src.y1()), dz + (src.z2() - src.z1())));
                for (int k = i + 10; k < t.length; k++) if (t[k].equalsIgnoreCase("move")) out.add(src);
            }
            default -> { return List.of(); }
        }
        if (exec && c.relative) return List.of();
        return out;
    }

    private static Region box(String label, int ax, int ay, int az, int bx, int by, int bz) {
        return new Region(label, Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }
}
