package fr.lyonmc.hub.noel;

import com.google.gson.GsonBuilder;
import fr.lyonmc.hub.player.HubPlayers;
import fr.lyonmc.hub.world.HubWorlds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bataille de boules de neige (comme le Tumble des consoles) : les joueurs commencent sur l'étage du haut et
 * cassent la neige sous les pieds des autres à coups de boules de neige. Celui qui tombe sous le dernier étage
 * est éliminé ; le dernier debout gagne. /bataille pour jouer.
 */
public final class SnowBattle implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String P = "<#9fd3ff>❄</#9fd3ff> <dark_gray>·</dark_gray> ";

    private enum State { IDLE, COUNTDOWN, RUNNING, ENDING }

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final HubPlayers players;
    private final ChristmasVillage village;
    private final Random random = new Random();
    private final File statsFile;
    private final YamlConfiguration stats;

    private SnowArena arena;
    private boolean arenaReady;
    private State state = State.IDLE;
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final List<UUID> alive = new ArrayList<>();
    private final List<UUID> fallen = new ArrayList<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Set<UUID> ownTeleport = new HashSet<>();
    /** Où chaque joueur attendait (au hub) : il y revient à la fin de la partie. */
    private final Map<UUID, Location> returnTo = new java.util.HashMap<>();
    /** Éliminés qui regardent la fin en spectateur. */
    private final Set<UUID> spectators = new HashSet<>();
    private BukkitTask task;
    private int countdown;
    private boolean forced;
    private long startedAt;
    private long goAt;
    private int meltDebt;

    SnowBattle(JavaPlugin plugin, HubWorlds worlds, HubPlayers players, ChristmasVillage village) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.players = players;
        this.village = village;
        this.statsFile = new File(plugin.getDataFolder(), "bataille.yml");
        this.stats = YamlConfiguration.loadConfiguration(statsFile);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("noel.bataille");
        if (c == null && plugin.getConfig().getDefaults() != null) c = plugin.getConfig().getDefaults().getConfigurationSection("noel.bataille");
        return c;
    }

    private int num(String key, int def) {
        ConfigurationSection c = cfg();
        return c == null ? def : c.getInt(key, def);
    }

    private void msg(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(P + mini));
    }

    public boolean enabled() {
        ConfigurationSection c = cfg();
        return village.open() && (c == null || c.getBoolean("actif", true));
    }

    public boolean running() {
        return state == State.RUNNING || state == State.ENDING;
    }

    public boolean playing(Player p) {
        return alive.contains(p.getUniqueId());
    }

    // ───────────────────────────── Arène ─────────────────────────────

    /** L'arène telle que décrite dans la config (recréée si la config a changé). */
    SnowArena arena() {
        Location c = village.arenaCenter();
        if (c == null) return null;
        int r = num("rayon", 14), f = num("etages", 3), g = num("ecart", 7), rel = num("relief", 2);
        boolean ice = cfg() == null || cfg().getBoolean("glace", true);
        if (arena == null || !arena.world.equals(c.getWorld()) || arena.cx != c.getBlockX() || arena.cy != c.getBlockY() || arena.cz != c.getBlockZ()
                || arena.radius != Math.max(5, Math.min(40, r)) || arena.floors != Math.max(1, Math.min(6, f))
                || arena.gap != Math.max(4, Math.min(20, g)) || arena.relief != Math.max(0, Math.min(4, rel))) {
            arena = new SnowArena(c.getWorld(), c.getBlockX(), c.getBlockY(), c.getBlockZ(), r, f, g, rel, ice);
            arenaReady = false;
        }
        return arena;
    }

    /** Refait les étages (nouveau relief). */
    public boolean rebuild() {
        SnowArena a = arena();
        if (a == null) return false;
        String now = describe(a);
        String before = village.builtArena();
        if (before != null && !before.equals(now)) {
            SnowArena old = parse(before);
            if (old != null) old.clear();
            plugin.getLogger().info("Bataille de boules de neige : ancienne arène retirée (" + before + "), nouvelle : " + now);
        }
        a.build(random.nextLong());
        village.builtArena(now);
        arenaReady = true;
        return true;
    }

    private static String describe(SnowArena a) {
        return a.world.getName() + ";" + a.cx + ";" + a.cy + ";" + a.cz + ";" + a.radius + ";" + a.floors + ";" + a.gap + ";" + a.relief;
    }

    private static SnowArena parse(String s) {
        try {
            String[] p = s.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new SnowArena(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Retire l'arène (avant de la déplacer). */
    void removeArena() {
        if (arena != null) arena.clear();
        arenaReady = false;
    }

    // ───────────────────────────── Commande /bataille ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission("lyonhub.admin");
        switch (sub) {
            case "top", "classement" -> sendTop(sender);
            case "quitter", "leave" -> {
                if (sender instanceof Player p) leave(p);
            }
            case "lancer", "start" -> {
                if (!admin) return deny(sender);
                if (state != State.IDLE && state != State.COUNTDOWN) {
                    msg(sender, "<gray>Une partie est déjà en cours.");
                    return true;
                }
                if (sender instanceof Player p && !alive.contains(p.getUniqueId())) queue.add(p.getUniqueId());
                cleanQueue();
                if (queue.isEmpty()) {
                    msg(sender, "<gray>Personne dans la file : <white>/bataille ajouter <joueur></white>.");
                    return true;
                }
                forced = true;
                startCountdown(state == State.COUNTDOWN ? Math.min(countdown, 5) : 5);
                msg(sender, "<gray>Partie lancée dans 5 secondes avec <white>" + queue.size() + "</white> joueur(s)"
                        + (queue.size() < 2 ? " <dark_gray>(partie d'essai : pas de récompense)" : "") + ".");
            }
            case "ajouter", "add" -> {
                if (!admin) return deny(sender);
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) {
                    msg(sender, "<gray>Usage : <white>/bataille ajouter <joueur>");
                    return true;
                }
                join(t);
                msg(sender, "<white>" + t.getName() + "</white> <gray>est dans la file.");
            }
            case "arene", "arène" -> {
                if (!admin) return deny(sender);
                if (running()) {
                    msg(sender, "<gray>Attends la fin de la partie.");
                    return true;
                }
                msg(sender, rebuild() ? "<gray>Arène refaite avec un nouveau relief." : "<red>L'arène de Noël n'est pas installée.");
            }
            case "fin", "stop" -> {
                if (!admin) return deny(sender);
                stopAll("<gray>Partie arrêtée par un admin.");
                msg(sender, "<gray>Partie arrêtée.");
            }
            case "", "jouer", "rejoindre", "join" -> {
                if (sender instanceof Player p) join(p);
                else msg(sender, "<gray>À faire en jeu.");
            }
            default -> {
                msg(sender, "<white><bold>Bataille de boules de neige</bold>");
                sender.sendMessage(MM.deserialize("  <white>/bataille</white> <dark_gray>rejoindre la prochaine partie"));
                sender.sendMessage(MM.deserialize("  <white>/bataille quitter</white> <dark_gray>quitter la file ou la partie"));
                sender.sendMessage(MM.deserialize("  <white>/bataille top</white> <dark_gray>les meilleurs joueurs"));
                if (admin) sender.sendMessage(MM.deserialize("  <white>/bataille lancer | ajouter <joueur> | arene | fin</white> <dark_gray>(admin)"));
            }
        }
        return true;
    }

    private boolean deny(CommandSender sender) {
        msg(sender, "<gray>Réservé aux admins.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("quitter", "top"));
            if (sender.hasPermission("lyonhub.admin")) out.addAll(List.of("lancer", "ajouter", "arene", "fin"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ajouter")) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    /** Actions des objets de la partie (lit rouge : quitter). */
    public void action(Player p, String what) {
        if (what.equals("quitter")) leave(p);
        else if (what.equals("jouer")) {
            if (queue.contains(p.getUniqueId())) leave(p);
            else join(p);
        }
    }

    // ───────────────────────────── File d'attente ─────────────────────────────

    public void join(Player p) {
        if (!enabled()) {
            msg(p, "<gray>La bataille de boules de neige n'est pas ouverte.");
            return;
        }
        if (alive.contains(p.getUniqueId())) {
            msg(p, "<gray>Tu es déjà en train de jouer !");
            return;
        }
        if (players.building(p)) {
            msg(p, "<gray>Quitte d'abord le mode construction (<white>/lyonhub construire</white>).");
            return;
        }
        if (!worlds.isHub(p.getWorld())) {
            // En survie : on rejoint la file depuis le hub (le Père Noël est au spawn)
            World hub = worlds.names().isEmpty() ? null : Bukkit.getWorld(worlds.names().get(0));
            if (hub == null) return;
            p.teleportAsync(hub.getSpawnLocation().add(0.5, 0, 0.5)).thenAccept(ok -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (ok && p.isOnline() && worlds.isHub(p.getWorld())) join(p);
            }, 10L));
            return;
        }
        // On attend là où on est (au hub) : la téléportation dans l'arène se fait au départ de la partie
        boolean wasQueued = !queue.add(p.getUniqueId());
        if (!village.isVillageWorld(p.getWorld())) returnTo.put(p.getUniqueId(), p.getLocation().clone());
        int min = Math.max(2, num("joueurs-min", 2));
        if (!wasQueued) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
            String when = running() ? "Une partie est en cours : tu joueras la suivante."
                    : queue.size() >= min ? "La partie commence bientôt, tu vas être téléporté dans l'arène !"
                    : "Tu seras téléporté dans l'arène dès qu'il y a <white>" + min + "</white> joueurs (" + queue.size() + "/" + min + ").";
            msg(p, "<gray>Tu es dans la file de la bataille de boules de neige. " + when
                    + " <dark_gray>[<click:run_command:'/bataille quitter'><hover:show_text:'<gray>Quitter la file'><red>quitter</red></hover></click>]");
            for (UUID u : queue) {
                Player o = Bukkit.getPlayer(u);
                if (o != null && !o.equals(p)) msg(o, "<white>" + p.getName() + "</white> <gray>rejoint la bataille (" + queue.size() + " dans la file).");
            }
            // Les autres joueurs du hub sont prévenus (une fois par partie à venir)
            if (queue.size() == 1 && !running()) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!o.equals(p) && worlds.isHub(o.getWorld())) {
                        o.sendMessage(MM.deserialize(P + "<white>" + p.getName() + "</white> <gray>attend des adversaires pour une bataille de boules de neige : "
                                + "<click:run_command:'/bataille'><hover:show_text:'<gray>Rejoindre'><white><u>/bataille</u></white></hover></click>"));
                    }
                }
            }
        } else {
            msg(p, "<gray>Tu es déjà dans la file (" + queue.size() + " joueur" + (queue.size() > 1 ? "s" : "") + ").");
        }
        if (state == State.IDLE && queue.size() >= min) startCountdown(num("attente-secondes", 20));
        else if (state == State.COUNTDOWN && queue.size() >= num("joueurs-max", 12) && countdown > 5) countdown = 5;
    }

    public void leave(Player p) {
        UUID u = p.getUniqueId();
        if (alive.contains(u)) {
            eliminate(p, "a abandonné");
            return;
        }
        returnTo.remove(u);
        if (queue.remove(u)) msg(p, "<gray>Tu as quitté la file de la bataille.");
        else msg(p, "<gray>Tu n'es pas dans la file.");
    }

    private void cleanQueue() {
        queue.removeIf(u -> {
            Player p = Bukkit.getPlayer(u);
            return p == null || !worlds.isHub(p.getWorld()) || players.building(p);
        });
    }

    // ───────────────────────────── Déroulement ─────────────────────────────

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    private void startCountdown(int seconds) {
        if (arena() == null) return;
        state = State.COUNTDOWN;
        countdown = Math.max(1, seconds);
        if (!arenaReady) rebuild();
        ensureTask();
    }

    private long ticks;

    private void tick() {
        ticks++;
        switch (state) {
            case COUNTDOWN -> {
                if (ticks % 4 != 0) return;
                cleanQueue();
                int min = Math.max(2, num("joueurs-min", 2));
                if (queue.isEmpty() || queue.size() < min && !forced) {
                    for (UUID u : queue) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.sendActionBar(MM.deserialize("<gray>Pas assez de joueurs : en attente (" + queue.size() + "/" + min + ")"));
                    }
                    state = State.IDLE;
                    forced = false;
                    stopTaskIfIdle();
                    return;
                }
                for (UUID u : queue) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null) continue;
                    p.sendActionBar(MM.deserialize("<#9fd3ff>❄ Bataille de boules de neige dans <white>" + countdown + " s</white> · " + queue.size() + " joueurs"));
                    if (countdown <= 5) {
                        p.showTitle(Title.title(MM.deserialize("<#9fd3ff><bold>" + countdown), MM.deserialize("<gray>Bataille de boules de neige"),
                                Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(100))));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.4f);
                    }
                }
                if (--countdown <= 0) start();
            }
            case RUNNING -> tickGame();
            default -> {
            }
        }
    }

    private void stopTaskIfIdle() {
        if (state == State.IDLE && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void start() {
        SnowArena a = arena();
        cleanQueue();
        if (a == null || queue.isEmpty()) {
            state = State.IDLE;
            stopTaskIfIdle();
            return;
        }
        if (!arenaReady) rebuild();
        arenaReady = false; // elle sera abîmée
        int max = Math.max(2, num("joueurs-max", 12));
        List<Player> list = new ArrayList<>();
        for (UUID u : new ArrayList<>(queue)) {
            if (list.size() >= max) break;
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            list.add(p);
            queue.remove(u);
        }
        alive.clear();
        fallen.clear();
        names.clear();
        List<Location> spawns = a.spawns(list.size(), random);
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            if (players.parkour().running(p)) players.parkour().action(p, "quitter");
            p.closeInventory();
            alive.add(p.getUniqueId());
            names.put(p.getUniqueId(), p.getName());
            ownTeleport.add(p.getUniqueId());
            p.teleportAsync(spawns.get(i)).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> ownTeleport.remove(p.getUniqueId())));
            players.removeHubItems(p);
            players.placeHubItem(p, 8, players.hubItem(Material.RED_BED, "<red><bold>Quitter la bataille</bold> <gray>(clic droit)",
                    List.of("<gray>Retour au bord de l'arène"), "bataille:quitter"));
            p.getInventory().setHeldItemSlot(0);
            p.setFallDistance(0);
            p.setFireTicks(0);
            var maxHp = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(maxHp == null ? 20 : maxHp.getValue());
            p.setFoodLevel(20);
            if (p.getGameMode() != GameMode.ADVENTURE && !players.building(p)) p.setGameMode(GameMode.ADVENTURE);
            p.showTitle(Title.title(MM.deserialize("<#9fd3ff><bold>Bataille de neige !"), MM.deserialize("<gray>Casse la neige sous les pieds des autres"),
                    Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(300))));
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.6f, 1.4f);
        }
        startedAt = System.currentTimeMillis();
        goAt = startedAt + 3000;
        meltDebt = 0;
        kitGiven = false;
        state = State.RUNNING;
        forced = false;
        plugin.getLogger().info("Bataille de boules de neige : partie lancée avec " + list.size() + " joueur(s) (" + String.join(", ", names.values()) + ").");
        exportAsync();
    }

    private boolean kitGiven;

    private void tickGame() {
        SnowArena a = arena;
        long now = System.currentTimeMillis();
        long elapsed = (now - startedAt) / 1000;
        // Départ : les boules de neige arrivent après 3 secondes
        if (now >= goAt && !kitGiven) {
            kitGiven = true;
            for (UUID u : alive) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                refill(p);
                p.showTitle(Title.title(MM.deserialize("<white><bold>Go !"), Component.empty(),
                        Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(700), java.time.Duration.ofMillis(200))));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
        } else if (now < goAt && ticks % 4 == 0) {
            int left = (int) Math.ceil((goAt - now) / 1000.0);
            for (UUID u : alive) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.sendActionBar(MM.deserialize("<gray>Les boules de neige arrivent dans <white>" + left + "</white>…"));
            }
        }
        // Chutes et départs
        for (UUID u : new ArrayList<>(alive)) {
            if (state != State.RUNNING) break; // le gagnant est connu
            Player p = Bukkit.getPlayer(u);
            if (p == null) {
                eliminate(u, null, "s'est déconnecté");
                continue;
            }
            // Pendant la téléportation de départ, le joueur n'est pas encore dans l'arène
            if (ownTeleport.contains(u) || now < startedAt + 1500) continue;
            Location l = p.getLocation();
            if (!l.getWorld().equals(a.world) || l.getY() < a.eliminationY() || !a.contains(l, 3)) eliminate(p, "est tombé");
        }
        if (state != State.RUNNING) return;
        // Chaque seconde : boules de neige rechargées et infos
        if (ticks % 4 == 0 && kitGiven) {
            int fonteApres = num("fonte-apres-secondes", 75);
            for (UUID u : alive) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                refill(p);
                String melt = elapsed >= fonteApres ? " · <#ff9f9f>la neige fond !" : "";
                p.sendActionBar(MM.deserialize("<#9fd3ff>❄ <white>" + alive.size() + "</white> <gray>encore debout · " + time(elapsed) + melt));
            }
        }
        // La neige fond (pour finir les parties trop longues)
        int fonteApres = num("fonte-apres-secondes", 75);
        if (elapsed >= fonteApres && a.total() > 0) {
            int duration = Math.max(10, num("fonte-duree-secondes", 90));
            meltDebt += a.total();
            int per = meltDebt / (duration * 4);
            meltDebt -= per * duration * 4;
            Block b = a.melt(per);
            if (b != null && ticks % 4 == 0) a.world.playSound(b.getLocation(), Sound.BLOCK_SNOW_BREAK, 0.6f, 0.8f);
        }
        if (elapsed >= num("duree-max-secondes", 300)) end(null, true);
    }

    private static String time(long s) {
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private ItemStack snowballs() {
        ItemStack it = new ItemStack(Material.SNOWBALL, 16);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<white><bold>Boules de neige</bold> <gray>(clic droit)").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(MM.deserialize("<gray>Vise la neige sous les autres !").decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return players.gameItem(it);
    }

    private void refill(Player p) {
        ItemStack cur = p.getInventory().getItem(0);
        if (cur != null && cur.getType() == Material.SNOWBALL && cur.getAmount() >= 16 && players.isGameItem(cur)) return;
        if (cur != null && !cur.getType().isAir() && !players.isHubItem(cur)) {
            // Un objet reçu au hub est dans la case : on le met ailleurs (il n'est jamais perdu)
            players.placeHubItem(p, 0, snowballs());
            return;
        }
        p.getInventory().setItem(0, snowballs());
    }

    private void eliminate(Player p, String why) {
        eliminate(p.getUniqueId(), p, why, true);
    }

    private void eliminate(UUID u, Player p, String why) {
        eliminate(u, p, why, true);
    }

    private void eliminate(UUID u, Player p, String why, boolean teleport) {
        if (!alive.remove(u)) return;
        fallen.add(u);
        String name = names.getOrDefault(u, p == null ? "?" : p.getName());
        int place = alive.size() + 1;
        if (p != null && p.isOnline()) {
            if (teleport && arena != null && p.getWorld().equals(arena.world)) spectate(p);
            else backToVillage(p, false);
            p.showTitle(Title.title(MM.deserialize("<#ff9f9f><bold>Éliminé"), MM.deserialize("<gray>" + place + (place == 1 ? "er" : "e") + " sur " + names.size()),
                    Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(300))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8f, 1f);
            if (teleport && !alive.isEmpty()) msg(p, "<gray>Tu regardes la fin en spectateur : retour au hub à la fin de la partie (ou tout de suite avec <white>/hub</white>).");
        }
        for (UUID o : names.keySet()) {
            Player op = Bukkit.getPlayer(o);
            if (op != null && !o.equals(u)) msg(op, "<white>" + name + "</white> <gray>" + why + " ! " + (alive.isEmpty() ? "" : "Encore <white>" + alive.size() + "</white> debout."));
        }
        if (state != State.RUNNING) return;
        if (names.size() >= 2 && alive.size() <= 1) end(alive.isEmpty() ? u : alive.get(0), false);
        else if (alive.isEmpty()) end(null, false);
    }

    /** Éliminé : en spectateur, au-dessus de l'arène, tourné vers le centre. */
    private void spectate(Player p) {
        players.removeHubItems(p);
        players.giveHubItems(p);
        spectators.add(p.getUniqueId());
        players.setMode(p, GameMode.SPECTATOR);
        SnowArena a = arena;
        Location view = new Location(a.world, a.cx + 0.5, a.cy + a.relief + 9, a.cz - a.radius - 5 + 0.5, 0, 32);
        ownTeleport.add(p.getUniqueId());
        p.teleportAsync(view).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> ownTeleport.remove(p.getUniqueId())));
    }

    /** Fin du mode spectateur (retour en aventure). */
    private void unspectate(Player p) {
        if (spectators.remove(p.getUniqueId()) && p.getGameMode() == GameMode.SPECTATOR) players.setMode(p, GameMode.ADVENTURE);
    }

    /** Fin de partie pour un joueur : objets du hub rendus, retour au point d'attente (s'il est encore au hub). */
    private void backToVillage(Player p, boolean teleport) {
        if (!worlds.isHub(p.getWorld()) || players.building(p)) return;
        players.removeHubItems(p);
        players.giveHubItems(p);
        p.setFallDistance(0);
        Location lobby = village.lobby();
        if (teleport && lobby != null) {
            ownTeleport.add(p.getUniqueId());
            p.teleportAsync(lobby).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> ownTeleport.remove(p.getUniqueId())));
        }
    }

    private void end(UUID winner, boolean timeUp) {
        if (state != State.RUNNING) return;
        state = State.ENDING;
        kitGiven = false;
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        boolean real = names.size() >= 2;
        String winnerName = winner == null ? null : names.get(winner);
        // Statistiques
        if (real) {
            for (Map.Entry<UUID, String> e : names.entrySet()) {
                String k = "joueurs." + e.getKey();
                stats.set(k + ".pseudo", e.getValue());
                stats.set(k + ".parties", stats.getInt(k + ".parties") + 1);
            }
            stats.set("parties", stats.getInt("parties") + 1);
            stats.set("derniere.gagnant", winnerName);
            stats.set("derniere.joueurs", new ArrayList<>(names.values()));
            stats.set("derniere.date", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            stats.set("derniere.secondes", seconds);
        }
        int wins = 0;
        if (real && winner != null) {
            String k = "joueurs." + winner;
            wins = stats.getInt(k + ".victoires") + 1;
            stats.set(k + ".victoires", wins);
        }
        // Le gagnant
        Player wp = winner == null ? null : Bukkit.getPlayer(winner);
        if (winnerName != null) {
            String others = names.size() - 1 + " adversaire" + (names.size() > 2 ? "s" : "");
            Bukkit.broadcast(MM.deserialize(P + "<white>" + winnerName + "</white> <gray>gagne la bataille de boules de neige de l'arène de Noël ! <dark_gray>("
                    + others + ", " + time(seconds) + ")"));
            if (wp != null) {
                wp.showTitle(Title.title(MM.deserialize("<#ffd86b><bold>Victoire !"), MM.deserialize("<gray>Le roi de la neige, c'est toi"),
                        Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(500))));
                wp.playSound(wp.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                firework(wp.getLocation());
            }
            if (real) reward(winner, winnerName, wins);
        } else {
            for (UUID u : names.keySet()) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) msg(p, timeUp ? "<gray>Temps écoulé : personne ne gagne cette fois." : "<gray>Partie terminée.");
            }
        }
        saveStats();
        exportAsync();
        plugin.getLogger().info("Bataille de boules de neige : " + (winnerName == null ? "pas de gagnant" : winnerName + " gagne")
                + " (" + names.size() + " joueurs, " + seconds + " s).");
        // Rejouer ?
        for (UUID u : names.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !queue.contains(u)) {
                p.sendMessage(MM.deserialize(P + "<click:run_command:'/bataille'><hover:show_text:'<gray>Rejoindre la prochaine partie'><#9fd3ff><u>Rejouer</u></#9fd3ff></hover></click>"
                        + " <dark_gray>·</dark_gray> <click:run_command:'/bataille top'><gray><u>meilleurs joueurs</u></gray></click>"));
            }
        }
        // Après quelques secondes : tout le monde revient, l'arène est refaite
        List<UUID> played = new ArrayList<>(names.keySet());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : new ArrayList<>(alive)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) backToVillage(p, false);
            }
            alive.clear();
            for (UUID u : played) {
                Player p = Bukkit.getPlayer(u);
                Location back = returnTo.remove(u);
                if (p != null) unspectate(p);
                if (p == null || !village.isVillageWorld(p.getWorld())) continue;
                if (back == null || back.getWorld() == null) {
                    World hub = worlds.names().isEmpty() ? null : Bukkit.getWorld(worlds.names().get(0));
                    back = hub == null ? null : hub.getSpawnLocation().add(0.5, 0, 0.5);
                }
                if (back != null) p.teleportAsync(back);
            }
            state = State.IDLE;
            rebuild();
            exportAsync(); // le site voit que la partie est finie
            cleanQueue();
            if (queue.size() >= Math.max(2, num("joueurs-min", 2))) startCountdown(num("attente-secondes", 20));
            else stopTaskIfIdle();
        }, 80L);
    }

    private void reward(UUID winner, String name, int wins) {
        ConfigurationSection c = cfg();
        if (c == null) return;
        String day = LocalDate.now().toString();
        int paid = stats.getInt("payees." + day + "." + winner);
        int cap = c.getInt("victoires-payees-par-jour", 5);
        if (cap <= 0 || paid < cap) {
            for (String cmd : c.getStringList("recompense-victoire")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", name));
            }
            ConfigurationSection old = stats.getConfigurationSection("payees");
            if (old != null) for (String d : old.getKeys(false)) if (!d.equals(day)) stats.set("payees." + d, null); // seulement le jour en cours
            stats.set("payees." + day + "." + winner, paid + 1);
            String m = c.getString("message-victoire", "");
            Player p = Bukkit.getPlayer(winner);
            if (p != null && m != null && !m.isBlank()) p.sendMessage(MM.deserialize(m));
        }
        if (wins == c.getInt("titre-victoires", 5)) {
            String cmd = c.getString("titre-commande", "");
            if (cmd != null && !cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", name));
        }
    }

    private void firework(Location l) {
        Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.fromRGB(0x9FD3FF), Color.WHITE)
                    .withFade(Color.fromRGB(0xFF6B6B)).trail(true).build());
            m.setPower(0);
            f.setFireworkMeta(m);
        });
        Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 2L);
    }

    /** Arrêt (admin, rechargement, arrêt du serveur) : tout le monde récupère les objets du hub. */
    public void stopAll(String message) {
        for (UUID u : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                if (message != null) msg(p, message);
                backToVillage(p, true);
            }
        }
        for (UUID u : new ArrayList<>(spectators)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                unspectate(p);
                backToVillage(p, true);
            }
        }
        spectators.clear();
        alive.clear();
        queue.clear();
        state = State.IDLE;
        kitGiven = false;
        forced = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** À l'arrêt du serveur : les objets de la partie sont retirés (sans téléportation). */
    public void shutdown() {
        for (UUID u : alive) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) players.removeHubItems(p);
        }
        for (UUID u : new ArrayList<>(spectators)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) unspectate(p);
        }
        alive.clear();
        queue.clear();
        state = State.IDLE;
        if (task != null) task.cancel();
        task = null;
        saveStats();
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    /** Une boule de neige casse le bloc de neige touché, et pousse le joueur touché. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball) || !(ball.getShooter() instanceof Player shooter)) return;
        if (state != State.RUNNING || !alive.contains(shooter.getUniqueId()) || arena == null) return;
        Block b = e.getHitBlock();
        if (b != null && arena.breakable(b)) {
            b.setType(Material.AIR, false);
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 14, 0.3, 0.3, 0.3, Material.SNOW_BLOCK.createBlockData());
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_SNOW_BREAK, 0.9f, 1.1f);
        }
        if (e.getHitEntity() instanceof Player victim && alive.contains(victim.getUniqueId()) && !victim.equals(shooter)) {
            Vector dir = ball.getVelocity().setY(0);
            if (dir.lengthSquared() < 1.0E-4) dir = victim.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 1.0E-4) dir.normalize();
            victim.setVelocity(dir.multiply(0.55).setY(0.32));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1.2, 0), 10, 0.3, 0.3, 0.3, 0.02);
            victim.playSound(victim.getLocation(), Sound.ENTITY_SNOW_GOLEM_HURT, 0.6f, 1.4f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Snowball s && s.getShooter() instanceof Player p && alive.contains(p.getUniqueId())
                && System.currentTimeMillis() < goAt) e.setCancelled(true);
    }

    /** Pas de dégâts pendant la partie (chutes, boules de neige) : on ne fait que tomber. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && alive.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectate(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE || !spectators.contains(e.getPlayer().getUniqueId())) return;
        if (arena == null || e.getTo() == null || !arena.world.equals(e.getTo().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!alive.contains(p.getUniqueId()) || ownTeleport.contains(p.getUniqueId())) return;
        // Une autre téléportation (commande, menu...) : le joueur quitte la partie, et reste là où il est allé
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (alive.contains(p.getUniqueId())) eliminate(p.getUniqueId(), p, "a quitté la partie", false);
        });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // Du hub au village (ou l'inverse) on reste dans la file ; en partant en survie, on la quitte
        if (!worlds.isHub(p.getWorld())) queue.remove(p.getUniqueId());
        // Un spectateur qui part (ex. /hub) redevient joueur normal
        if (spectators.contains(p.getUniqueId()) && (arena == null || !p.getWorld().equals(arena.world))) unspectate(p);
        // Arriver dans le monde de l'arène au départ de la partie, ce n'est pas la quitter
        if (alive.contains(p.getUniqueId()) && (arena == null || !p.getWorld().equals(arena.world))) {
            boolean inHub = worlds.isHub(p.getWorld());
            eliminate(p.getUniqueId(), inHub ? p : null, "a quitté l'arène", false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        queue.remove(p.getUniqueId());
        ownTeleport.remove(p.getUniqueId());
        returnTo.remove(p.getUniqueId());
        unspectate(p);
        if (alive.contains(p.getUniqueId())) {
            players.removeHubItems(p);
            eliminate(p.getUniqueId(), null, "s'est déconnecté");
        }
    }

    // ───────────────────────────── Texte des PNJ de l'arène ─────────────────────────────

    /** Lignes au-dessus des PNJ de l'arène (au hub et au village), avec les chiffres en direct. */
    public List<String> npcLabel() {
        int min = Math.max(2, num("joueurs-min", 2));
        String status;
        if (!enabled()) status = "<red>Fermée pour le moment";
        else if (state == State.RUNNING || state == State.ENDING) {
            status = "<#ff9f9f>● Partie en cours</#ff9f9f> <gray>· <white>" + alive.size() + "</white> debout";
        } else if (state == State.COUNTDOWN) {
            status = "<#7bd88f>● Départ dans <white>" + countdown + " s</white></#7bd88f> <gray>· <white>" + queue.size() + "</white> joueurs";
        } else if (!queue.isEmpty()) {
            status = "<#ffd86b>● <white>" + queue.size() + "/" + min + "</white> joueurs dans la file";
        } else {
            status = "<gray>● En attente de joueurs <dark_gray>(" + min + " min.)";
        }
        List<Map<String, Object>> best = top(1);
        int games = stats.getInt("parties");
        String record;
        if (best.isEmpty() || (int) best.get(0).get("victoires") == 0) record = "<gray>Pas encore de champion !";
        else {
            int v = (int) best.get(0).get("victoires");
            record = "<gray>Champion : <white>" + best.get(0).get("pseudo") + "</white> <dark_gray>· " + v + " victoire" + (v > 1 ? "s" : "");
        }
        return List.of(
                "<gradient:#ff6b6b:#ffffff:#ff6b6b><bold>❄ Arène de Noël ❄</bold></gradient>",
                "<#9fd3ff>Bataille de boules de neige" + (games > 0 ? " <dark_gray>· " + games + " partie" + (games > 1 ? "s" : "") : ""),
                status,
                record,
                "<#7bd88f><bold>▶ Clic pour jouer");
    }

    // ───────────────────────────── Classement et site ─────────────────────────────

    private List<Map<String, Object>> top(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        ConfigurationSection s = stats.getConfigurationSection("joueurs");
        if (s == null) return list;
        for (String k : s.getKeys(false)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pseudo", s.getString(k + ".pseudo", "?"));
            m.put("victoires", s.getInt(k + ".victoires"));
            m.put("parties", s.getInt(k + ".parties"));
            list.add(m);
        }
        list.sort((a, b) -> {
            int c = Integer.compare((int) b.get("victoires"), (int) a.get("victoires"));
            return c != 0 ? c : Integer.compare((int) a.get("parties"), (int) b.get("parties"));
        });
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    private void sendTop(CommandSender to) {
        List<Map<String, Object>> top = top(10);
        msg(to, "<white><bold>Bataille de boules de neige · meilleurs joueurs");
        if (top.isEmpty()) to.sendMessage(MM.deserialize("  <dark_gray>Pas encore de partie : <click:run_command:'/bataille'><white><u>/bataille</u></white></click> pour lancer la première !"));
        for (int i = 0; i < top.size(); i++) {
            Map<String, Object> m = top.get(i);
            int v = (int) m.get("victoires");
            to.sendMessage(MM.deserialize("  <white>" + (i + 1) + ".</white> <gray>" + m.get("pseudo") + "</gray> <dark_gray>·</dark_gray> <#9fd3ff>"
                    + v + " victoire" + (v > 1 ? "s" : "") + "</#9fd3ff> <dark_gray>(" + m.get("parties") + " parties)"));
        }
    }

    private void saveStats() {
        try {
            stats.save(statsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer bataille.yml", e);
        }
    }

    /** Fichier lu par la page Noël du site (bataille.json, à côté de classements.json). */
    private Path exportFile() {
        String p = plugin.getConfig().getString("classements", "siteweb/data/classements.json");
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            File root = plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
            path = root.toPath().resolve(p);
        }
        return path.resolveSibling("bataille.json");
    }

    public void exportAsync() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maj", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        m.put("ouverte", enabled());
        m.put("enCours", running());
        m.put("joueursMin", Math.max(2, num("joueurs-min", 2)));
        m.put("etages", num("etages", 3));
        m.put("parties", stats.getInt("parties"));
        m.put("top", top(10));
        if (stats.isSet("derniere.date")) {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("gagnant", stats.getString("derniere.gagnant"));
            last.put("joueurs", stats.getStringList("derniere.joueurs"));
            last.put("date", stats.getString("derniere.date"));
            last.put("secondes", stats.getLong("derniere.secondes"));
            m.put("derniere", last);
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(m);
        Path target = exportFile();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (target.getParent() == null || !Files.isDirectory(target.getParent())) return; // pas de site sur ce serveur
                Path tmp = target.resolveSibling("bataille.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "bataille.json non écrit", e);
            }
        });
    }
}
