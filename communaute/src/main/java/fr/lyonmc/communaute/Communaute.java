package fr.lyonmc.communaute;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.rewards.Bundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * COMMUNAUTE (par wassdead) : accueil des nouveaux, votes, parrainage et fidélité.
 * S'appuie sur LyonAchievements pour les pièces, les lots, les profils et l'affichage.
 */
public final class Communaute extends JavaPlugin implements TabExecutor {

    private LyonAchievements lyon;
    private Store store;
    private Welcome welcome;
    private Votes votes;
    private Referral referral;
    private Loyalty loyalty;
    private Goals goals;
    private HappyHours happyHours;
    private WebExport export;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        lyon = JavaPlugin.getPlugin(LyonAchievements.class);
        saveDefaultConfig();
        addMissingSections();
        store = new Store(this);
        store.load();

        welcome = new Welcome(this);
        votes = new Votes(this);
        referral = new Referral(this);
        loyalty = new Loyalty(this);
        goals = new Goals(this);
        happyHours = new HappyHours(this);
        export = new WebExport(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(welcome, this);
        pm.registerEvents(votes, this);
        pm.registerEvents(loyalty, this);
        pm.registerEvents(goals, this);
        votes.hookVotifier();

        // LyonAchievements affiche « AFK » dans TAB et un panneau « Communauté » sur le grand écran du hub
        getServer().getServicesManager().register(fr.lyonmc.achievements.api.AfkProvider.class, loyalty, this, ServicePriority.Normal);
        getServer().getServicesManager().register(fr.lyonmc.achievements.api.ScreenPanel.class, new Panel(this), this, ServicePriority.Normal);

        for (String c : List.of("vote", "premierspas", "parrain", "objectif", "bienvenue", "communaute")) {
            var cmd = getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) loyalty.joined(p, 0);
        startTasks();
        getLogger().info("COMMUNAUTE activé : " + store.all().size() + " joueurs connus.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (welcome != null) welcome.hideAll();
        if (store != null) {
            for (Player p : Bukkit.getOnlinePlayers()) store.get(p).lastSeen = System.currentTimeMillis();
            store.save(false);
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    private void startTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, loyalty::tickSecond, 20L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, welcome::tick, 40L, 40L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            referral.tick();
            votes.tick();
            goals.tick();
            happyHours.tick();
        }, 100L, 600L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, export::export, 200L, 1200L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> store.save(true), 2400L, 2400L));
    }

    /** Ajoute au config.yml du serveur les nouvelles parties d'une mise à jour, sans toucher au reste. */
    private void addMissingSections() {
        var cfg = getConfig();
        var defaults = cfg.getDefaults();
        if (defaults == null) return;
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!cfg.isSet(key)) {
                cfg.set(key, defaults.get(key));
                changed = true;
            }
        }
        if (changed) saveConfig();
    }

    // ───────────────────────────── Outils communs ─────────────────────────────

    public LyonAchievements lyon() {
        return lyon;
    }

    public Store store() {
        return store;
    }

    public Votes votes() {
        return votes;
    }

    public Referral referral() {
        return referral;
    }

    public Loyalty loyalty() {
        return loyalty;
    }

    public Goals goals() {
        return goals;
    }

    public HappyHours happyHours() {
        return happyHours;
    }

    public Welcome welcome() {
        return welcome;
    }

    /** Section de la config (celle du .jar si le fichier du serveur ne l'a pas). */
    public ConfigurationSection section(String path) {
        if (getConfig().isSet(path)) return getConfig().getConfigurationSection(path);
        return getConfig().getDefaults() == null ? null : getConfig().getDefaults().getConfigurationSection(path);
    }

    public Bundle bundle(String path) {
        return Bundle.of(section(path));
    }

    /** « 150 pièces, 15 points de saison » */
    public Component describe(String path) {
        List<Component> parts = lyon.rewards().describe(bundle(path));
        return parts.isEmpty() ? Component.empty() : Component.join(JoinConfiguration.separator(Component.text(", ", fr.lyonmc.achievements.util.Text.GRAY)), parts);
    }

    /** Donne un lot de la config à un joueur connecté. */
    public void give(Player p, String path) {
        lyon.rewards().grant(p, bundle(path));
    }

    /** Donne un lot maintenant, ou à la prochaine connexion si le joueur est hors ligne. */
    public void giveOrQueue(UUID uuid, String name, String path) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            give(online, path);
            online.sendMessage(lyon.prefixed("<gray>Récompense : ").append(describe(path)));
        } else {
            store.getOrCreate(uuid, name).pending.add(path);
        }
    }

    public void deliverPending(Player p) {
        PlayerData d = store.get(p);
        if (d.pending.isEmpty()) return;
        List<String> list = new ArrayList<>(d.pending);
        d.pending.clear();
        for (String path : list) {
            give(p, path);
            p.sendMessage(lyon.prefixed("<gray>Récompense reçue pendant ton absence : ").append(describe(path)));
        }
    }

    public Component parse(String mini, TagResolver... resolvers) {
        return lyon.text().parse(mini, resolvers);
    }

    public void msg(CommandSender to, String mini, TagResolver... resolvers) {
        lyon.msg(to, mini, resolvers);
    }

    public boolean sameIpAllowed() {
        return getConfig().getBoolean("anti-triche.meme-ip-autorise", false);
    }

    public boolean inHub(Player p) {
        return lyon.spawnHub() != null && lyon.spawnHub().inHub(p.getLocation());
    }

    public boolean isMine(World w) {
        World mine = lyon.resources() == null ? null : lyon.resources().world();
        return w != null && mine != null && w.equals(mine);
    }

    public ZoneId zone() {
        return lyon.text().zone();
    }

    public String month() {
        return YearMonth.now(zone()).toString();
    }

    public String week() {
        LocalDate d = LocalDate.now(zone());
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-S" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static String num(long n) {
        return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
    }

    // ───────────────────────────── Commandes ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("communaute")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        switch (name) {
            case "vote" -> votes.command(p);
            case "premierspas" -> welcome.command(p, args);
            case "parrain" -> referral.command(p, args);
            case "objectif" -> goals.command(p);
            case "bienvenue" -> welcome.welcomeCommand(p);
            default -> {
            }
        }
        return true;
    }

    private void admin(CommandSender s, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                addMissingSections();
                happyHours.reload();
                msg(s, "<gray>COMMUNAUTE rechargé.");
            }
            case "vote" -> {
                if (args.length < 2) {
                    msg(s, "<gray>Usage : <white>/communaute vote <joueur> [site]");
                    return;
                }
                votes.handleVote(args[1], args.length > 2 ? args[2] : "test");
                msg(s, "<gray>Vote simulé pour <white>" + args[1] + "</white>.");
            }
            case "bonus" -> {
                int minutes = 60;
                try {
                    if (args.length > 1) minutes = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                }
                happyHours.start("manuel", minutes * 60_000L);
                msg(s, "<gray>Heure bonus lancée pour <white>" + minutes + "</white> min.");
            }
            case "objectif" -> {
                goals.next(true);
                msg(s, "<gray>Nouvel objectif : <white>" + goals.currentName());
            }
            case "premierspas" -> {
                if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
                    msg(s, "<gray>Usage : <white>/communaute premierspas reset <joueur>");
                    return;
                }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) {
                    msg(s, "<gray>Joueur non connecté.");
                    return;
                }
                welcome.reset(t);
                msg(s, "<gray>Premiers pas remis à zéro pour <white>" + t.getName() + "</white>.");
            }
            default -> {
                msg(s, "<white><bold>COMMUNAUTE</bold></white> <dark_gray>par wassdead");
                for (String l : List.of(
                        "<white>/communaute reload</white> <dark_gray>recharger la config",
                        "<white>/communaute vote <joueur> [site]</white> <dark_gray>simuler un vote",
                        "<white>/communaute bonus [minutes]</white> <dark_gray>lancer une heure bonus",
                        "<white>/communaute objectif</white> <dark_gray>passer à l'objectif suivant",
                        "<white>/communaute premierspas reset <joueur></white> <dark_gray>recommencer les premiers pas")) {
                    s.sendMessage(parse("  " + l));
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("communaute") && args.length == 1) out.addAll(List.of("reload", "vote", "bonus", "objectif", "premierspas"));
        else if (name.equals("parrain") && args.length == 1) {
            out.add("top");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (name.equals("premierspas") && args.length == 1) out.addAll(List.of("cacher", "afficher"));
        else if (name.equals("communaute") && args.length == 2 && args[0].equalsIgnoreCase("vote")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
