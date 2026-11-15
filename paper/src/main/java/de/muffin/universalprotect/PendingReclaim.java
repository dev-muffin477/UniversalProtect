package de.muffin.universalprotect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Items that must be taken back from players who were offline during a container rollback. Applied when they join. */
public final class PendingReclaim {
    public record Entry(String uuid, String player, String item, int amount, long time) {}

    private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;
    private final File file;

    public PendingReclaim(File dataFolder) { this.file = new File(dataFolder, "pending-reclaim.txt"); }

    private static String enc(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static String dec(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }

    private List<Entry> read() {
        List<Entry> out = new ArrayList<>();
        if (!file.isFile()) return out;
        long now = System.currentTimeMillis();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length < 5) continue;
                try {
                    Entry e = new Entry(f[0], dec(f[1]), dec(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4]));
                    if (now - e.time() < KEEP_MS) out.add(e);
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    private void write(List<Entry> list) {
        try {
            if (list.isEmpty()) { Files.deleteIfExists(file.toPath()); return; }
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            List<String> lines = new ArrayList<>();
            for (Entry e : list) {
                lines.add(e.uuid() + "\t" + enc(e.player() == null ? "" : e.player()) + "\t" + enc(e.item()) + "\t" + e.amount() + "\t" + e.time());
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    public synchronized void add(String uuid, String player, String item, int amount) {
        List<Entry> list = read();
        list.add(new Entry(uuid, player, item, amount, System.currentTimeMillis()));
        write(list);
    }

    public synchronized List<Entry> take(String uuid) {
        List<Entry> mine = new ArrayList<>(), rest = new ArrayList<>();
        for (Entry e : read()) {
            if (e.uuid().equals(uuid)) mine.add(e); else rest.add(e);
        }
        if (!mine.isEmpty()) write(rest);
        return mine;
    }

    public synchronized int count() { return read().size(); }
}
