package fr.lyonmc.achievements;

import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.boss.BossCommand;
import fr.lyonmc.achievements.boss.BossService;
import fr.lyonmc.achievements.command.MenuCommands;
import fr.lyonmc.achievements.command.SuccesCommand;
import fr.lyonmc.achievements.core.Configs;
import fr.lyonmc.achievements.core.ProfileStore;
import fr.lyonmc.achievements.custom.CustomAdvancements;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.discord.DiscordService;
import fr.lyonmc.achievements.economy.EconomyCommand;
import fr.lyonmc.achievements.economy.EconomyService;
import fr.lyonmc.achievements.economy.VaultBridge;
import fr.lyonmc.achievements.hdv.AuctionCommand;
import fr.lyonmc.achievements.hdv.AuctionService;
import fr.lyonmc.achievements.holo.HologramService;
import fr.lyonmc.achievements.listener.AdvancementListener;
import fr.lyonmc.achievements.listener.ChatListener;
import fr.lyonmc.achievements.listener.MotdListener;
import fr.lyonmc.achievements.menu.AchievementsMenu;
import fr.lyonmc.achievements.menu.HeadService;
import fr.lyonmc.achievements.menu.HubMenu;
import fr.lyonmc.achievements.menu.MenuListener;
import fr.lyonmc.achievements.missions.MissionListener;
import fr.lyonmc.achievements.missions.MissionMenu;
import fr.lyonmc.achievements.missions.MissionService;
import fr.lyonmc.achievements.rewards.DailyRewardService;
import fr.lyonmc.achievements.rewards.RewardMenus;
import fr.lyonmc.achievements.rewards.RewardService;
import fr.lyonmc.achievements.season.SeasonMenu;
import fr.lyonmc.achievements.season.SeasonService;
import fr.lyonmc.achievements.team.TeamCommand;
import fr.lyonmc.achievements.team.TeamService;
import fr.lyonmc.achievements.teleport.TeleportCommands;
import fr.lyonmc.achievements.teleport.Teleporter;
import fr.lyonmc.achievements.util.Text;
import fr.lyonmc.achievements.web.WebExportService;
import fr.lyonmc.achievements.world.ResourceWorldService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class LyonAchievements extends JavaPlugin {

    private static final int CONFIG_VERSION = 6;

    private Configs configs;
    private Text text;
    private AdvancementRegistry registry;
    private StatsService stats;
    private ProfileStore profiles;
    private BoardManager boards;
    private ChatListener chat;
    private fr.lyonmc.achievements.teleport.HubService spawnHub;
    private fr.lyonmc.achievements.rewards.GradeService grades;
    private MotdListener motd;
    private HeadService heads;

    private EconomyService economy;
    private RewardService rewards;
    private DailyRewardService daily;
    private RewardMenus rewardMenus;
    private MissionService missions;
    private MissionListener missionListener;
    private MissionMenu missionMenu;
    private CustomAdvancements custom;
    private SeasonService seasons;
    private SeasonMenu seasonMenu;
    private TeamService teams;
    private TeamCommand teamCommand;
    private Teleporter teleporter;
    private TeleportCommands teleportCommands;
    private AuctionService auctions;
    private AuctionCommand auctionCommand;
    private BossService boss;
    private HologramService holograms;
    private DiscordService discord;
    private ResourceWorldService resources;
    private AchievementsMenu achievementsMenu;
    private HubMenu hub;
    private WebExportService web;

    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onLoad() {
        // Vault doit connaître notre économie avant que les plugins de boutique démarrent
        VaultBridge.register(this);
    }

    @Override
    public void onEnable() {
        migrateConfig();
        saveDefaultConfig();
        configs = new Configs(this);
        configs.load();

        text = new Text(this);
        profiles = new ProfileStore(this);
        profiles.load();
        discord = new DiscordService(this);

        // Succès LyonMC à créer avant de charger la liste des succès
        custom = new CustomAdvancements(this);
        custom.register();

        registry = new AdvancementRegistry(this);
        registry.load();
        stats = new StatsService(this, registry);
        stats.load();

        economy = new EconomyService(this);
        grades = new fr.lyonmc.achievements.rewards.GradeService(this);
        grades.load();
        rewards = new RewardService(this);
        rewards.load();
        daily = new DailyRewardService(this);
        daily.load();
        rewardMenus = new RewardMenus(this);
        missions = new MissionService(this);
        missions.load();
        missionMenu = new MissionMenu(this);
        missionListener = new MissionListener(this);
        seasons = new SeasonService(this);
        seasons.init();
        seasonMenu = new SeasonMenu(this);
        teams = new TeamService(this);
        teams.load();
        teamCommand = new TeamCommand(this);
        teleporter = new Teleporter(this);
        teleportCommands = new TeleportCommands(this);
        spawnHub = new fr.lyonmc.achievements.teleport.HubService(this);
        auctions = new AuctionService(this);
        auctions.load();
        auctionCommand = new AuctionCommand(this);
        boss = new BossService(this);
        boss.cleanup();
        heads = new HeadService(this);
        for (Player p : Bukkit.getOnlinePlayers()) heads.remember(p);
        holograms = new HologramService(this);
        holograms.cleanup();
        holograms.load();
        resources = new ResourceWorldService(this);
        try {
            resources.init();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Monde ressources indisponible", e);
        }

        boards = new BoardManager(this, registry, stats, text);
        chat = new ChatListener(this, registry, stats, text);
        motd = new MotdListener(this, registry, stats, text);
        achievementsMenu = new AchievementsMenu(registry, stats, text, heads);
        hub = new HubMenu(this);
        achievementsMenu.setHub(p -> hub.open(p));
        web = new WebExportService(this);

        listen(new AdvancementListener(this, registry, stats, boards, text), chat, new MenuListener(this), heads, motd,
                missionListener, custom, teamCommand, teleporter, teleportCommands, spawnHub, boss, web);

        SuccesCommand succes = new SuccesCommand(this, registry, stats, boards, achievementsMenu, text);
        command(succes, "succes", "classement", "carte");
        command(new MenuCommands(this), "menu", "missions", "titres", "cosmetiques", "boutique", "parcours", "recompense",
                "saison", "ressources", "lmc");
        command(new EconomyCommand(this), "money", "pay", "baltop", "eco");
        command(teamCommand, "equipe");
        command(teleportCommands, "sethome", "home", "delhome", "homes", "spawn", "setspawn", "back", "tpa", "tpahere",
                "tpaccept", "tpdeny", "rtp");
        command(auctionCommand, "hdv");
        command(new BossCommand(this), "boss");

        // Joueurs déjà connectés (cas d'un /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            stats.startSession(p.getUniqueId());
            stats.refreshOnline(p);
            boards.create(p);
            missions.ensure(profiles.get(p));
        }
        stats.rebuildRanking();

        if (getConfig().getBoolean("settings.import-on-startup", true)) {
            stats.importFromWorld(count -> getLogger().info("Import terminé : " + count + " fichiers de succès analysés."));
        }

        startTasks();
        getLogger().info("LyonAchievements activé : " + registry.total() + " succès, " + rewards.paliers().size()
                + " paliers, " + rewards.titles().size() + " titres.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (rewards != null) rewards.stop();
        if (boss != null) boss.end(false, null);
        if (holograms != null) holograms.removeAll();
        if (boards != null) boards.removeAll();
        if (stats != null) stats.saveSync();
        if (profiles != null) profiles.saveSync();
        if (teams != null) teams.save();
        if (auctions != null) auctions.save();
    }

    private void listen(Listener... listeners) {
        for (Listener l : listeners) getServer().getPluginManager().registerEvents(l, this);
    }

    private void command(TabExecutor executor, String... names) {
        for (String name : names) {
            PluginCommand pc = getCommand(name);
            if (pc == null) {
                getLogger().warning("Commande absente de plugin.yml : " + name);
                continue;
            }
            pc.setExecutor(executor);
            pc.setTabCompleter(executor);
        }
    }

    public void startTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();

        long interval = Math.max(5L, getConfig().getLong("settings.update-interval-ticks", 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, boards::tick, 1L, interval));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) grades.check(p);
            chat.refreshAll();
        }, 2L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, motd::refresh, 20L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this,
                () -> heads.preload(stats.ranking().subList(0, Math.min(10, stats.ranking().size()))), 200L, 12000L));

        // Toutes les secondes : boss
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, boss::tick, 20L, 20L));
        // Toutes les 20 secondes : distance des missions, planning du boss, hologrammes
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            missionListener.tickDistance();
            boss.tickSchedule();
            holograms.update();
        }, 100L, 400L));
        // Chaque minute : changement de jour, saison, HDV, monde ressources, progrès basés sur les stats
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            missions.tick();
            seasons.tick();
            auctions.tick();
            resources.tick();
            custom.tick();
        }, 600L, 1200L));
        // Toutes les 5 minutes : paliers liés au temps de jeu
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) rewards.check(p);
        }, 6000L, 6000L));

        // Site web : export des données
        long webTicks = Math.max(10L, getConfig().getLong("web.interval-seconds", 60L)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, web::export, 200L, webTicks));

        long saveTicks = Math.max(1L, getConfig().getLong("settings.autosave-minutes", 5L)) * 60L * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            stats.saveAsync();
            profiles.saveAsync();
        }, saveTicks, saveTicks));
    }

    /** Sauvegarde l'ancienne config et installe la nouvelle quand la version change. */
    private void migrateConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        if (YamlConfiguration.loadConfiguration(file).getInt("config-version", 1) >= CONFIG_VERSION) return;
        File backup = new File(getDataFolder(), "config-ancienne.yml");
        if (backup.exists() && !backup.delete()) getLogger().warning("Impossible de remplacer config-ancienne.yml");
        if (file.renameTo(backup)) {
            getLogger().info("Nouvelle configuration installée (l'ancienne est dans config-ancienne.yml).");
        }
        saveDefaultConfig();
        reloadConfig();
    }

    /** /lmc reload */
    public void reloadEverything() {
        reloadConfig();
        configs.load();
        text.reload();
        registry.load();
        grades.load();
        rewards.load();
        daily.load();
        missions.load();
        holograms.load();
        web.invalidateContent();
        for (Player p : Bukkit.getOnlinePlayers()) stats.refreshOnline(p);
        stats.recomputeAll();
        stats.rebuildRanking();
        boards.recreateAll();
        startTasks();
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public boolean module(String id) {
        return getConfig().getBoolean("modules." + id, true);
    }

    public Component prefixed(String mini, TagResolver... resolvers) {
        return text.parse(getConfig().getString("messages.prefix", "") + mini, resolvers);
    }

    public void msg(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(prefixed(mini, resolvers));
    }

    public Configs configs() { return configs; }
    public Text text() { return text; }
    public AdvancementRegistry registry() { return registry; }
    public StatsService stats() { return stats; }
    public ProfileStore profiles() { return profiles; }
    public BoardManager boards() { return boards; }
    public HeadService heads() { return heads; }
    public EconomyService economy() { return economy; }
    public RewardService rewards() { return rewards; }
    public DailyRewardService daily() { return daily; }
    public RewardMenus rewardMenus() { return rewardMenus; }
    public MissionService missions() { return missions; }
    public MissionMenu missionMenu() { return missionMenu; }
    public CustomAdvancements custom() { return custom; }
    public SeasonService seasons() { return seasons; }
    public SeasonMenu seasonMenu() { return seasonMenu; }
    public TeamService teams() { return teams; }
    public TeamCommand teamCommand() { return teamCommand; }
    public Teleporter teleporter() { return teleporter; }
    public TeleportCommands teleportCommands() { return teleportCommands; }
    public AuctionService auctions() { return auctions; }
    public AuctionCommand auctionCommand() { return auctionCommand; }
    public BossService boss() { return boss; }
    public HologramService holograms() { return holograms; }
    public DiscordService discord() { return discord; }
    public ResourceWorldService resources() { return resources; }
    public AchievementsMenu achievementsMenu() { return achievementsMenu; }
    public HubMenu hub() { return hub; }
    public ChatListener chat() { return chat; }
    public fr.lyonmc.achievements.teleport.HubService spawnHub() { return spawnHub; }
    public fr.lyonmc.achievements.rewards.GradeService grades() { return grades; }
    public WebExportService web() { return web; }
}
