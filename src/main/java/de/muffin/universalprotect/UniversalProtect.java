package de.muffin.universalprotect;

import de.muffin.universalprotect.api.UniversalProtectAPI;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.concurrent.ConcurrentHashMap;

public final class UniversalProtect extends JavaPlugin {
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alerts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alertMuted = ConcurrentHashMap.newKeySet();
    private volatile boolean consumerActive = true;

    private final Settings settings = new Settings();
    private Blacklist blacklist;
    private DatabaseManager db;
    private RollbackEngine rollback;
    private Migrator migrator;
    private CoreProtectImporter importer;
    private Compactor compactor;
    private RollbackJournal journal;
    private StaffAudit audit;
    private Investigator investigator;
    private final AnomalyDetector anomaly = new AnomalyDetector();
    private ActionLogger actionLogger;
    private NetworkHandler network;
    private UniversalProtectAPI api;

    private static final int BSTATS_ID = 35314;

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
        importer = new CoreProtectImporter(db, getLogger(), UnaryOperator.identity());
        anomaly.setSink(this::onAnomaly);
        compactor = new Compactor(db, getLogger());
        journal = new RollbackJournal(db, getLogger());
        audit = new StaffAudit(db, getLogger());
        investigator = new Investigator(db, getLogger());
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

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> blacklist.reloadIfChanged(), 200L, 200L);

        int days = settings.autoPurgeDays();
        if (days > 0) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> db.autoPurge(days), 600L);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> db.autoPurge(settings.autoPurgeDays()), 20L * 3600 * 24, 20L * 3600 * 24);
        }
        int aggDays = getConfig().getInt("retention.aggregate-after-days", 0);
        if (aggDays > 0) {
            long span = Math.max(7, aggDays) * 86_400_000L;
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> compactor.start(Compactor.logReporter(getLogger()), span, false), 1200L);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> compactor.start(Compactor.logReporter(getLogger()), span, false), 20L * 3600 * 24, 20L * 3600 * 24);
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> rollback.resumePending(), 200L);
        if (BSTATS_ID > 0 && getConfig().getBoolean("metrics.enabled", true)) {
            Metrics metrics = new Metrics(this, BSTATS_ID);
            metrics.addCustomChart(new SimplePie("database_type",
                    () -> getConfig().getString("database.type", "duckdb")));
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

    private void onAnomaly(AnomalyDetector.Alert a) {
        getLogger().warning("[Anomaly] " + a.player() + " " + a.summary() + " @ " + a.world() + " " + a.x() + " " + a.y() + " " + a.z());
        String w = a.world() == null ? "" : a.world().replace("'", "");
        String text = Msg.PREFIX + "<" + a.color() + ">\u26a0 " + a.label() + "</" + a.color() + "> <green>" + Msg.esc(a.player()) + "</green> <gray>" + Msg.esc(a.summary())
                + "</gray> <dark_gray>[<hover:show_text:'Teleport'><click:run_command:'/up tp " + w + " " + a.x() + " " + a.y() + " " + a.z() + "'>"
                + a.x() + " " + a.y() + " " + a.z() + "</click></hover>]</dark_gray>";
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                for (UUID id : alerts) {
                    Player target = Bukkit.getPlayer(id);
                    if (target != null && target.hasPermission("universalprotect.admin")) target.sendMessage(Msg.MM.deserialize(text));
                }
            });
        } catch (RuntimeException ignored) {  }
    }

    private boolean detectLegacySqlite() {
        File cfg = new File(getDataFolder(), "config.yml");
        File sqlite = new File(getDataFolder(), "database.db");
        File duck = new File(getDataFolder(), "database.duckdb");
        if (!sqlite.isFile() || duck.exists()) return false;
        if (!cfg.isFile()) return true;
        return !org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(cfg).isSet("database.type");
    }

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
    public CoreProtectImporter importer() { return importer; }
    public Compactor compactor() { return compactor; }
    public RollbackJournal journal() { return journal; }
    public StaffAudit audit() { return audit; }
    public Investigator investigator() { return investigator; }
    public AnomalyDetector anomaly() { return anomaly; }
    public ActionLogger actionLogger() { return actionLogger; }
    public NetworkHandler network() { return network; }

    public boolean isConsumerActive() { return consumerActive; }
    public void setConsumerActive(boolean active) { this.consumerActive = active; }
    public Set<UUID> getInspectingPlayers() { return inspecting; }
    public Set<UUID> getAlertPlayers() { return alerts; }
    public Set<UUID> getAlertMuted() { return alertMuted; }
}
