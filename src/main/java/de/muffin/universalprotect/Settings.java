package de.muffin.universalprotect;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public final class Settings {
    public static final List<String> OPTION_KEYS = List.of(
            "log.block", "log.block-changes", "log.container", "log.items", "log.pickup", "log.interact",
            "log.signs", "log.frames", "log.chat", "log.commands", "log.kills", "log.deaths", "log.sessions",
            "log.inventory", "log.spawns", "log.usernames", "log.trades", "log.ender", "log.command-blocks");

    private volatile Map<String, Boolean> global = Map.of();
    private volatile Map<String, Map<String, Boolean>> perWorld = Map.of();
    private volatile Set<Material> alertBlocks = Set.of();
    private volatile long alertCooldownMs = 10_000L;
    private volatile int autoPurgeDays;
    private volatile int previewMaxBlocks = 20_000;
    private volatile long previewTimeoutMs = 120_000L;
    private volatile long rollbackBudgetMs = 20L;
    private volatile int defaultLines = 15;
    private volatile int maxLines = 100;
    private volatile boolean networking = true;

    private static boolean defaultFor(String key) { return !key.equals("log.pickup"); }

    public static void registerDefaults(FileConfiguration c) {
        for (String key : OPTION_KEYS) c.addDefault(key, defaultFor(key));
        c.addDefault("alerts.watch-blocks", new ArrayList<String>());
        c.addDefault("alerts.cooldown-seconds", 10);
        c.addDefault("discord.enabled", true);
        c.addDefault("discord.alerts-webhook", "");
        c.addDefault("discord.audit-webhook", "");
        c.addDefault("discord.min-level", 2);
        c.addDefault("discord.audit-events", new ArrayList<>(java.util.List.of("rollback", "restore", "undo", "purge", "migrate-db", "import-coreprotect", "compact")));
        c.addDefault("discord.startup-errors", true);
        c.addDefault("discord.server-name", "");
        c.addDefault("auto-purge-days", 0);
        c.addDefault("lookup.default-lines", 15);
        c.addDefault("lookup.max-lines", 100);
        c.addDefault("preview.max-blocks", 20000);
        c.addDefault("preview.timeout-seconds", 120);
        c.addDefault("rollback.time-budget-ms", 20);
        c.addDefault("networking.enabled", true);
        c.addDefault("database.type", "duckdb");
        c.addDefault("database.sqlite.file", "database.db");
        c.addDefault("database.duckdb.file", "database.duckdb");
        c.addDefault("database.mysql.host", "localhost");
        c.addDefault("database.mysql.port", 3306);
        c.addDefault("database.mysql.database", "universalprotect");
        c.addDefault("database.mysql.username", "root");
        c.addDefault("database.mysql.password", "");
        c.addDefault("database.mysql.parameters", "useSSL=false&allowPublicKeyRetrieval=true");
        c.addDefault("database.clickhouse.host", "localhost");
        c.addDefault("database.clickhouse.port", 8123);
        c.addDefault("database.clickhouse.database", "universalprotect");
        c.addDefault("database.clickhouse.username", "default");
        c.addDefault("database.clickhouse.password", "");
        c.addDefault("database.clickhouse.ssl", false);
        AnomalyDetector.registerDefaults(c::addDefault);
        c.addDefault("retention.aggregate-after-days", 0);
    }

    public void load(UniversalProtect plugin) {
        FileConfiguration c = plugin.getConfig();

        Map<String, Boolean> g = new HashMap<>();
        for (String key : OPTION_KEYS) g.put(key, c.getBoolean(key, defaultFor(key)));
        global = g;

        Map<String, Map<String, Boolean>> pw = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            File f = new File(plugin.getDataFolder(), w.getName() + ".yml");
            if (!f.isFile()) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            Map<String, Boolean> o = new HashMap<>();
            for (String key : OPTION_KEYS) if (y.isSet(key)) o.put(key, y.getBoolean(key));
            if (!o.isEmpty()) {
                pw.put(w.getName().toLowerCase(Locale.ROOT), o);
                plugin.getLogger().info("Loaded per-world configuration " + f.getName() + " (" + o.size() + " overrides)");
            }
        }
        perWorld = pw;

        Set<Material> s = EnumSet.noneOf(Material.class);
        for (String n : c.getStringList("alerts.watch-blocks")) {
            Material mat = Material.matchMaterial(n);
            if (mat != null) s.add(mat);
        }
        alertBlocks = s;
        alertCooldownMs = Math.max(0, c.getLong("alerts.cooldown-seconds", 10)) * 1000L;
        autoPurgeDays = Math.max(0, c.getInt("auto-purge-days", 0));
        defaultLines = Math.max(1, c.getInt("lookup.default-lines", 15));
        maxLines = Math.max(defaultLines, c.getInt("lookup.max-lines", 100));
        Pager.configure(c.getInt("chat.width-px", 300));
        previewMaxBlocks = Math.max(0, c.getInt("preview.max-blocks", 20000));
        previewTimeoutMs = Math.max(10, c.getInt("preview.timeout-seconds", 120)) * 1000L;
        rollbackBudgetMs = Math.max(1, c.getLong("rollback.time-budget-ms", 20));
        networking = c.getBoolean("networking.enabled", true);

        plugin.anomaly().configure(new AnomalyDetector.ConfigReader() {
            @Override public boolean bool(String path, boolean def) { return c.getBoolean(path, def); }
            @Override public int integer(String path, int def) { return c.getInt(path, def); }
            @Override public String string(String path, String def) { return c.getString(path, def); }
            @Override public List<String> list(String path) { return c.getStringList(path); }
        });
    }

    public boolean opt(String key, String world) {
        if (world != null) {
            Map<String, Boolean> o = perWorld.get(world.toLowerCase(Locale.ROOT));
            if (o != null && o.containsKey(key)) return o.get(key);
        }
        return global.getOrDefault(key, true);
    }

    public Set<Material> alertBlocks() { return alertBlocks; }
    public long alertCooldownMs() { return alertCooldownMs; }
    public int autoPurgeDays() { return autoPurgeDays; }
    public int previewMaxBlocks() { return previewMaxBlocks; }
    public long previewTimeoutMs() { return previewTimeoutMs; }
    public long rollbackBudgetMs() { return rollbackBudgetMs; }
    public int defaultLines() { return defaultLines; }
    public int maxLines() { return maxLines; }
    public boolean networking() { return networking; }
}
