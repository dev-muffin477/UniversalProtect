package de.muffin.universalprotect;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import de.muffin.universalprotect.api.UniversalProtectAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class UniversalProtect implements ModInitializer {
    private static UniversalProtect instance;
    private static UniversalProtectAPI apiInstance;

    private final Logger logger = Logger.getLogger("UniversalProtect");
    private final DiscordNotifier discord = new DiscordNotifier(logger);
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alerts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alertMuted = ConcurrentHashMap.newKeySet();
    private volatile boolean consumerActive = true;
    private volatile boolean enabled = false;

    private File dataFolder;
    private Cfg config;
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
    private Hooks hooks;
    private NetworkHandler network;
    private CommandHandler handler;

    @Override
    public void onInitialize() {
        instance = this;
        dataFolder = new File(FabricLoader.getInstance().getConfigDir().toFile(), "universalprotect");
        dataFolder.mkdirs();
        File cfgFile = new File(dataFolder, "config.yml");
        try (InputStream in = UniversalProtect.class.getResourceAsStream("/config.yml")) {
            Cfg.writeDefault(cfgFile, in);
        } catch (Exception e) {
            logger.warning("Could not write default config: " + e);
        }
        loadConfig();

        handler = new CommandHandler(this);
        hooks = new Hooks(this);
        hooks.register();
        network = new NetworkHandler(this);
        if (settings.networking()) network.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String name : new String[]{"up", "universalprotect", "uprotect"}) {
                LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                        .executes(ctx -> run(ctx.getSource(), ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> suggest(ctx.getSource(), b))
                                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "args"))));
                dispatcher.register(root);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Mc.setServer(server);
            start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
    }

    private void loadConfig() {
        config = Cfg.load(new File(dataFolder, "config.yml"));
        Settings.registerDefaults(config);
        settings.load(this);
        discord.reload(discordConf());
    }

    private void start() {
        Sched.init();
        blacklist = new Blacklist(dataFolder);
        blacklist.load();

        DbType type = DbType.parse(config.getString("database.type", "duckdb"));
        if (type == null) {
            logger.warning("Unknown database.type, falling back to duckdb.");
            type = DbType.DUCKDB;
        }
        try {
            db = new DatabaseManager(this, type);
        } catch (Throwable t) {
            logger.severe("Could not open the " + type.id() + " database: " + t);
            discord.startupError("Database unavailable", "Could not open the " + type.id() + " database: " + t);
            t.printStackTrace();
            return;
        }
        logger.info("Using database engine: " + type.id());

        rollback = new RollbackEngine(this, db);
        migrator = new Migrator(this, db);
        importer = new CoreProtectImporter(db, logger, (UnaryOperator<String>) Mc::normalizeWorld);
        anomaly.setSink(this::onAnomaly);
        compactor = new Compactor(db, logger);
        Sched.asyncLater(200, () -> rollback.resumePending());
        journal = new RollbackJournal(db, logger);
        audit = new StaffAudit(db, logger);
        investigator = new Investigator(db, logger);
        apiInstance = new UniversalProtectAPI(this);
        enabled = true;

        Sched.asyncTimer(200, 200, () -> blacklist.reloadIfChanged());
        int days = settings.autoPurgeDays();
        if (days > 0) {
            Sched.asyncLater(600, () -> db.autoPurge(days));
            Sched.asyncTimer(20L * 3600 * 24, 20L * 3600 * 24, () -> db.autoPurge(settings.autoPurgeDays()));
        }
        int aggDays = config.getInt("retention.aggregate-after-days", 0);
        if (aggDays > 0) {
            long span = Math.max(7, aggDays) * 86_400_000L;
            Sched.asyncLater(1200, () -> compactor.start(Compactor.logReporter(logger), span, false));
            Sched.asyncTimer(20L * 3600 * 24, 20L * 3600 * 24, () -> compactor.start(Compactor.logReporter(logger), span, false));
        }
        logger.info("UniversalProtect " + version() + " enabled.");
    }

    private void stop() {
        enabled = false;
        apiInstance = null;
        Sched.shutdown();
        if (db != null) db.close();
        discord.shutdown();
    }

    private int run(CommandSourceStack src, String line) {
        if (!enabled) {
            src.sendSystemMessage(Mini.parse("<red>UniversalProtect is not active (database could not be opened)."));
            return 0;
        }
        String[] args = line.isBlank() ? new String[0] : line.trim().split("\\s+");
        try {
            handler.run(new Sender(src), args);
        } catch (Throwable t) {
            logger.severe("Command failed: " + t);
            t.printStackTrace();
            src.sendSystemMessage(Mini.parse("<red>Internal error, see the server log."));
        }
        return 1;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(CommandSourceStack src, SuggestionsBuilder b) {
        if (enabled) {
            try {
                String rem = b.getRemaining();
                String[] parts = rem.isEmpty() ? new String[0] : rem.split("\\s+", -1);
                List<String> args = new ArrayList<>(List.of(parts));
                if (args.isEmpty() || rem.endsWith(" ")) {
                    if (!args.isEmpty() && args.get(args.size() - 1).isEmpty()) {  } else args.add("");
                }
                int lastStart = rem.lastIndexOf(' ') + 1;
                SuggestionsBuilder sub = b.createOffset(b.getStart() + lastStart);
                List<String> out = handler.complete(new Sender(src), args.toArray(new String[0]));
                if (out != null) for (String s : out) sub.suggest(s);
                return sub.buildFuture();
            } catch (Throwable ignored) {
            }
        }
        return b.buildFuture();
    }

    private void onAnomaly(AnomalyDetector.Alert a) {
        discord.alert(a.player(), a.rule(), a.level(), a.label(), a.summary(), a.world(), a.x(), a.y(), a.z());
        logger.warning("[Anomaly] " + a.player() + " " + a.summary() + " @ " + a.world() + " " + a.x() + " " + a.y() + " " + a.z());
        String w = a.world() == null ? "" : a.world().replace("'", "");
        String text = Msg.PREFIX + "<" + a.color() + ">\u26a0 " + a.label() + "</" + a.color() + "> <green>" + Msg.esc(a.player()) + "</green> <gray>" + Msg.esc(a.summary())
                + "</gray> <dark_gray>[<hover:show_text:'Teleport'><click:run_command:'/up tp " + w + " " + a.x() + " " + a.y() + " " + a.z() + "'>"
                + a.x() + " " + a.y() + " " + a.z() + "</click></hover>]</dark_gray>";
        Sched.main(() -> {
            for (UUID id : alerts) {
                var target = Mc.player(id);
                if (target != null && Perm.has(target.createCommandSourceStack(), "universalprotect.admin")) target.sendSystemMessage(Mini.parse(text));
            }
        });
    }

    public void reload() {
        loadConfig();
        if (blacklist != null) blacklist.load();
    }

    public void saveDatabaseType(String id) {
        try {
            Cfg.replaceDatabaseType(new File(dataFolder, "config.yml"), id);
            config = Cfg.load(new File(dataFolder, "config.yml"));
            Settings.registerDefaults(config);
        } catch (Exception e) {
            logger.warning("Could not save database.type: " + e);
        }
    }

    public String version() {
        return FabricLoader.getInstance().getModContainer("universalprotect")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("2.0.0");
    }

    public static UniversalProtect get() { return instance; }
    public static UniversalProtectAPI api() { return apiInstance; }

    public boolean isEnabled() { return enabled; }
    public Logger getLogger() { return logger; }
    public File getDataFolder() { return dataFolder; }
    public Cfg config() { return config; }
    public Settings settings() { return settings; }
    public Blacklist blacklist() { return blacklist; }
    public DatabaseManager db() { return db; }
    public RollbackEngine rollback() { return rollback; }
    public Migrator migrator() { return migrator; }
    public CoreProtectImporter importer() { return importer; }
    public Compactor compactor() { return compactor; }
    public RollbackJournal journal() { return journal; }
    public StaffAudit audit() { return audit; }
    public DiscordNotifier discord() { return discord; }

    private DiscordNotifier.Conf discordConf() {
        String alerts = config.getString("discord.alerts-webhook", "");
        if (alerts == null || alerts.isBlank()) alerts = config.getString("detection.discord-webhook", "");
        String audit = config.getString("discord.audit-webhook", "");
        java.util.Set<String> ev = new java.util.HashSet<>();
        for (String s : config.getStringList("discord.audit-events")) ev.add(s.toLowerCase(java.util.Locale.ROOT));
        return new DiscordNotifier.Conf(config.getBoolean("discord.enabled", true),
                alerts == null ? "" : alerts.trim(), audit == null ? "" : audit.trim(),
                Math.max(1, Math.min(3, config.getInt("discord.min-level", 2))), ev,
                config.getBoolean("discord.startup-errors", true), config.getString("discord.server-name", ""));
    }
    public Investigator investigator() { return investigator; }
    public AnomalyDetector anomaly() { return anomaly; }
    public Hooks hooks() { return hooks; }
    public NetworkHandler network() { return network; }

    public boolean isConsumerActive() { return consumerActive; }
    public void setConsumerActive(boolean active) { this.consumerActive = active; }
    public Set<UUID> getInspectingPlayers() { return inspecting; }
    public Set<UUID> getAlertPlayers() { return alerts; }
    public Set<UUID> getAlertMuted() { return alertMuted; }
}
