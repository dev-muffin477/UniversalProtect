package de.muffin.universalprotect;

import de.muffin.universalprotect.api.UniversalProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UniversalProtect – Block-, Container-, Entity- und Chat-Logging mit Rollback/Restore (CoreProtect-Ersatz). */
public final class UniversalProtect extends JavaPlugin {
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alerts = ConcurrentHashMap.newKeySet();
    private volatile boolean consumerActive = true;

    private final Settings settings = new Settings();
    private Blacklist blacklist;
    private DatabaseManager db;
    private RollbackEngine rollback;
    private Migrator migrator;
    private ActionLogger actionLogger;
    private NetworkHandler network;
    private UniversalProtectAPI api;

    @Override
    public void onEnable() {
        boolean legacySqlite = detectLegacySqlite();
        saveDefaultConfig();
        Settings.registerDefaults(getConfig());
        getConfig().options().copyDefaults(true);
        if (legacySqlite) {
            getConfig().set("database.type", "sqlite");
            getLogger().info("Existing SQLite database found - continuing with SQLite. "
                    + "Switch to DuckDB live with: /up migrate-db duckdb (console).");
        }
        saveConfig();
        settings.load(this);

        blacklist = new Blacklist(getDataFolder());
        blacklist.load();

        DbType type = DbType.parse(getConfig().getString("database.type", "duckdb"));
        if (type == null) {
            getLogger().warning("Unknown database.type, falling back to duckdb.");
            type = DbType.DUCKDB;
        }
        try {
            db = new DatabaseManager(this, type);
        } catch (Throwable t) {
            getLogger().severe("Could not open the " + type.id() + " database: " + t);
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Using database engine: " + type.id());

        rollback = new RollbackEngine(this, db);
        migrator = new Migrator(this, db);
        actionLogger = new ActionLogger(this);

        network = new NetworkHandler(this);
        if (settings.networking()) network.register();

        CommandHandler handler = new CommandHandler(this);
        PluginCommand cmd = getCommand("up");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(actionLogger, this);
        getServer().getPluginManager().registerEvents(new InspectorListener(this), this);

        api = new UniversalProtectAPI(this);
        getServer().getServicesManager().register(UniversalProtectAPI.class, api, this, ServicePriority.Normal);

        for (Player p : Bukkit.getOnlinePlayers()) db.playerSeen(p.getUniqueId(), p.getName());

        // Blacklist bei Änderung der Datei automatisch neu laden
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> blacklist.reloadIfChanged(), 200L, 200L);

        int days = settings.autoPurgeDays();
        if (days > 0) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> db.autoPurge(days), 600L);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> db.autoPurge(settings.autoPurgeDays()), 20L * 3600 * 24, 20L * 3600 * 24);
        }
        getLogger().info("UniversalProtect " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (api != null) getServer().getServicesManager().unregister(api);
        if (network != null) network.unregister();
        getServer().getScheduler().cancelTasks(this);
        if (db != null) db.close();
    }

    /** Alte Installation (nur SQLite-Datei, kein database.type in der config.yml)? */
    private boolean detectLegacySqlite() {
        File cfg = new File(getDataFolder(), "config.yml");
        File sqlite = new File(getDataFolder(), "database.db");
        File duck = new File(getDataFolder(), "database.duckdb");
        if (!sqlite.isFile() || duck.exists()) return false;
        if (!cfg.isFile()) return true;
        return !org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(cfg).isSet("database.type");
    }

    /** Lädt config.yml, Welt-Dateien und blacklist.txt neu. */
    public void reload() {
        reloadConfig();
        Settings.registerDefaults(getConfig());
        settings.load(this);
        blacklist.load();
    }

    public Settings settings() { return settings; }
    public Blacklist blacklist() { return blacklist; }
    public DatabaseManager db() { return db; }
    public RollbackEngine rollback() { return rollback; }
    public Migrator migrator() { return migrator; }
    public ActionLogger actionLogger() { return actionLogger; }
    public NetworkHandler network() { return network; }

    public boolean isConsumerActive() { return consumerActive; }
    public void setConsumerActive(boolean active) { this.consumerActive = active; }
    public Set<UUID> getInspectingPlayers() { return inspecting; }
    public Set<UUID> getAlertPlayers() { return alerts; }
}
