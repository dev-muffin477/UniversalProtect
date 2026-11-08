package de.muffin.universalprotect;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Minimaler YAML-Konfigurationszugriff mit Punkt-Pfaden und Standardwerten (ersetzt Bukkits FileConfiguration). */
public final class Cfg {
    private Map<String, Object> data;
    private final Map<String, Object> defaults = new LinkedHashMap<>();

    private Cfg(Map<String, Object> data) { this.data = data; }

    public static Cfg empty() { return new Cfg(new LinkedHashMap<>()); }

    @SuppressWarnings("unchecked")
    public static Cfg load(File f) {
        if (f == null || !f.isFile()) return empty();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);
            if (o instanceof Map<?, ?> m) return new Cfg(new LinkedHashMap<>((Map<String, Object>) m));
        } catch (Exception e) {
            System.err.println("[UniversalProtect] Could not read " + f.getName() + ": " + e.getMessage());
        }
        return empty();
    }

    public void addDefault(String path, Object value) { defaults.put(path, value); }

    @SuppressWarnings("unchecked")
    private Object raw(String path) {
        Object cur = data;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(part)) { cur = null; break; }
            cur = ((Map<String, Object>) m).get(part);
        }
        if (cur != null) return cur;
        return defaults.get(path);
    }

    public boolean isSet(String path) {
        Object cur = data;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(part)) return false;
            cur = m.get(part);
        }
        return true;
    }

    public String getString(String path, String def) {
        Object o = raw(path);
        return o == null ? def : String.valueOf(o);
    }

    public String getString(String path) { return getString(path, null); }

    public int getInt(String path, int def) {
        Object o = raw(path);
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    public long getLong(String path, long def) {
        Object o = raw(path);
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? def : Long.parseLong(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    public boolean getBoolean(String path, boolean def) {
        Object o = raw(path);
        if (o instanceof Boolean b) return b;
        return o == null ? def : Boolean.parseBoolean(String.valueOf(o));
    }

    public List<String> getStringList(String path) {
        Object o = raw(path);
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    /** Teilabschnitt oder null, falls es ihn weder in der Datei noch in den Standardwerten gibt. */
    @SuppressWarnings("unchecked")
    public Cfg getConfigurationSection(String path) {
        Object o = raw(path);
        Cfg c;
        if (o instanceof Map<?, ?> m) c = new Cfg((Map<String, Object>) m);
        else c = new Cfg(new LinkedHashMap<>());
        String prefix = path + ".";
        boolean any = o instanceof Map;
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            if (e.getKey().startsWith(prefix)) { c.defaults.put(e.getKey().substring(prefix.length()), e.getValue()); any = true; }
        }
        return any ? c : null;
    }

    public static void writeDefault(File target, java.io.InputStream resource) throws IOException {
        if (target.exists() || resource == null) return;
        target.getParentFile().mkdirs();
        Files.copy(resource, target.toPath());
    }

    /** Schreibt einen Wert per Textersetzung in die Datei, ohne Kommentare zu verlieren (nur für database.type). */
    public static void replaceDatabaseType(File f, String type) throws IOException {
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        String out = text.replaceFirst("(?m)^(\\s*type:\\s*)\\S+", "$1" + type);
        Files.writeString(f.toPath(), out, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static Yaml dumper() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(o);
    }
}
