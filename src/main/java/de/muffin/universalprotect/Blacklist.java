package de.muffin.universalprotect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Dynamische Blacklist ({@code blacklist.txt}); wird bei Änderung der Datei automatisch neu geladen.
 *
 * <pre>
 * Notch                         -> Benutzer ignorieren (auch #tnt, #dispenser usw.)
 * /login                        -> Befehl ignorieren (Präfix-Vergleich)
 * minecraft:stone               -> Block / Entity / Item ignorieren
 * minecraft:shears@#dispenser   -> nur wenn der Benutzer #dispenser ist
 * </pre>
 * Kommentare beginnen mit "//" oder "# " (Raute + Leerzeichen).
 */
public final class Blacklist {
    private static final String HEADER = """
            // UniversalProtect blacklist.txt - one entry per line, reloaded automatically when changed.
            //   Notch                        ignore a user (also #tnt, #dispenser, ...)
            //   /login                       ignore a command (prefix match)
            //   minecraft:stone              ignore a block / entity / item
            //   minecraft:shears@#dispenser  ignore the id only when done by this user
            """;

    private final File file;
    private volatile long lastModified = -1;
    private volatile Set<String> users = Set.of();
    private volatile List<String> commands = List.of();
    private volatile Set<String> ids = Set.of();
    private volatile Map<String, Set<String>> idUsers = Map.of();

    public Blacklist(File dataFolder) {
        this.file = new File(dataFolder, "blacklist.txt");
    }

    public synchronized void load() {
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.writeString(file.toPath(), HEADER, StandardCharsets.UTF_8);
            }
            Set<String> u = new HashSet<>();
            List<String> c = new ArrayList<>();
            Set<String> i = new HashSet<>();
            Map<String, Set<String>> iu = new HashMap<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("//") || s.startsWith("# ") || s.equals("#")) continue;
                s = s.toLowerCase(Locale.ROOT);
                int at = s.indexOf('@');
                if (at > 0 && at < s.length() - 1) {
                    String id = normalizeId(s.substring(0, at));
                    iu.computeIfAbsent(id, k -> new HashSet<>()).add(s.substring(at + 1));
                } else if (s.startsWith("/")) {
                    c.add(s.substring(1));
                } else if (s.indexOf(':') > 0) {
                    i.add(normalizeId(s));
                } else {
                    u.add(s);
                }
            }
            users = u; commands = c; ids = i; idUsers = iu;
            lastModified = file.lastModified();
        } catch (IOException e) {
            // Blacklist ist optional: bei Lesefehler bleibt der letzte Stand aktiv
        }
    }

    /** Lädt neu, falls die Datei seit dem letzten Laden verändert wurde. */
    public void reloadIfChanged() {
        if (file.exists() && file.lastModified() != lastModified) load();
    }

    private static String normalizeId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    public boolean isBlocked(LogEntry e) {
        String player = e.player() == null ? null : e.player().toLowerCase(Locale.ROOT);
        if (player != null && users.contains(player)) return true;

        if (Actions.COMMAND.equals(e.action())) {
            String cmd = e.data() == null ? "" : e.data().toLowerCase(Locale.ROOT);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            for (String c : commands) if (cmd.equals(c) || cmd.startsWith(c + " ")) return true;
            return false;
        }

        String id = idOf(e);
        if (id == null) return false;
        if (ids.contains(id)) return true;
        Set<String> onlyFor = idUsers.get(id);
        return onlyFor != null && player != null && onlyFor.contains(player);
    }

    /** Ermittelt die Namespaced-ID, die ein Eintrag betrifft (Block, Item oder Entity). */
    private static String idOf(LogEntry e) {
        String data = e.data();
        if (data == null || data.isEmpty()) return null;
        String a = e.action();
        if (Actions.BLOCK.contains(a)) return data.startsWith("frame:") ? null : Actions.blockId(data);
        if (Actions.KILL.equals(a) || Actions.SPAWN.equals(a)) {
            int c = data.indexOf(':');
            return normalizeId(c > 0 ? data.substring(0, c) : data);
        }
        if (Actions.CONTAINER.contains(a) || Actions.INVENTORY.contains(a) || Actions.ITEM_ALL.contains(a)) {
            int sp = data.indexOf(' ');
            return normalizeId(sp > 0 ? data.substring(0, sp) : data);
        }
        if (Actions.USE.equals(a)) return normalizeId(data);
        return null;
    }
}
