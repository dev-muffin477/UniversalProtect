package de.muffin.universalprotect;

import java.util.Locale;

public final class Causes {
    public enum Kind {
        PLAYER("Player"), EXPLOSION("Explosion"), FIRE("Fire/Lava"), MOB("Mob"), NATURE("Nature"), PLUGIN("Plugin/Tool");
        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }

    private Causes() {}

    public static Kind kind(String name) {
        if (name == null || name.isEmpty() || name.charAt(0) != '#') return Kind.PLAYER;
        String n = name.substring(1).toLowerCase(Locale.ROOT);
        if (n.contains("worldedit") || n.contains("fawe") || n.contains("plugin") || n.contains("script") || n.equals("api")) return Kind.PLUGIN;
        if (n.contains("explo") || n.contains("tnt") || n.contains("creeper") || n.contains("fireball") || n.contains("crystal")
                || n.contains("wither") || n.contains("dragon") || n.contains("bed") || n.contains("anchor")) return Kind.EXPLOSION;
        if (n.contains("fire") || n.contains("lava") || n.contains("burn") || n.contains("magma") || n.contains("hot_floor")) return Kind.FIRE;
        if (n.equals("decay") || n.startsWith("fall") || n.contains("drown") || n.contains("starv") || n.contains("suffocat")
                || n.equals("void") || n.equals("contact") || n.contains("cramming") || n.contains("freez") || n.contains("melting")
                || n.contains("drying") || n.contains("lightning") || n.equals("gravity")) return Kind.NATURE;
        return Kind.MOB;
    }

    public static boolean isPlayer(String name) { return kind(name) == Kind.PLAYER; }

    public static String by(String meta) {
        if (meta == null) return null;
        if (meta.startsWith("by:")) return meta.substring(3);
        if (meta.startsWith("likely:")) return meta.substring(7);
        return null;
    }

    public static boolean inferred(String meta) { return meta != null && meta.startsWith("likely:"); }

    public static String pretty(String name) {
        if (name == null) return "?";
        String n = name.startsWith("#") ? name.substring(1) : name;
        return n.replace('_', ' ');
    }

    public static String label(String name, String meta) {
        if (kind(name) == Kind.PLAYER) return "<white>" + Msg.esc(name == null ? "?" : name) + "</white>";
        StringBuilder sb = new StringBuilder("<gold>").append(Msg.esc(pretty(name))).append("</gold>");
        String by = by(meta);
        if (by != null) sb.append(" <gray>(").append(inferred(meta) ? "probably by " : "caused by ").append(Msg.esc(by)).append(")</gray>");
        return sb.toString();
    }
}
