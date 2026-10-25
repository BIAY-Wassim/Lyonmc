package fr.lyonmc.communaute;

import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.PlayerStats;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accueil des nouveaux : premiers pas guidés (barre en haut de l'écran), kit et livre de départ,
 * et /bienvenue pour que les joueurs accueillent les nouveaux.
 */
public final class Welcome implements Listener {

    private record Newcomer(UUID uuid, String name, long until, String ip, Set<UUID> welcomedBy) {
    }

    private final Communaute plugin;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final List<Newcomer> newcomers = new ArrayList<>();

    Welcome(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("accueil.enabled", true);
    }

    // ───────────────────────────── Étapes ─────────────────────────────

    /** Étapes affichées (celle de la protection seulement si GriefPrevention est installé). */
    private List<String> steps() {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = plugin.section("accueil.etapes");
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            if (id.equals("protection") && griefPrevention() == null) continue;
            if (id.equals("metier") && (plugin.lyon().jobs() == null || !plugin.lyon().jobs().enabled())) continue;
            out.add(id);
        }
        return out;
    }

    private String text(String id) {
        ConfigurationSection s = plugin.section("accueil.etapes." + id);
        return s == null ? id : s.getString("texte", id);
    }

    private static Plugin griefPrevention() {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        return gp != null && gp.isEnabled() ? gp : null;
    }

    /** Nombre de zones protégées du joueur dans GriefPrevention (0 si inconnu). */
    private static int claims(Player p) {
        Plugin gp = griefPrevention();
        if (gp == null) return 0;
        try {
            Object dataStore = gp.getClass().getField("dataStore").get(gp);
            Object data = dataStore.getClass().getMethod("getPlayerData", UUID.class).invoke(dataStore, p.getUniqueId());
            Object list = data.getClass().getMethod("getClaims").invoke(data);
            return list instanceof Collection<?> c ? c.size() : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private boolean isDone(Player p, PlayerData d, String id) {
        Profile pr = plugin.lyon().profiles().get(p);
        return switch (id) {
            case "survie" -> !plugin.inHub(p) && !plugin.isMine(p.getWorld());
            case "recompense" -> !plugin.lyon().daily().canClaim(pr);
            case "home" -> !pr.homes.isEmpty();
            case "protection" -> claims(p) > 0;
            case "mission" -> pr.missionsDone > 0 || pr.daily.stream().anyMatch(m -> m.done) || pr.weekly.stream().anyMatch(m -> m.done);
            case "mine" -> d.visitedMine || plugin.isMine(p.getWorld());
            case "chat" -> d.saidHello;
            case "metier" -> {
                var jobs = plugin.lyon().jobs();
                // Un niveau gagné dans n'importe quel métier (tous commencent au niveau 1)
                yield jobs != null && jobs.totalLevels(pr) > jobs.all().size();
            }
            case "succes" -> {
                PlayerStats s = plugin.lyon().stats().get(p.getUniqueId());
                ConfigurationSection c = plugin.section("accueil.etapes.succes");
                yield s != null && s.count() >= (c == null ? 10 : c.getInt("objectif", 10));
            }
            default -> false;
        };
    }

    /** Toutes les 2 secondes : étapes réussies et barre en haut de l'écran. */
    void tick() {
        if (!enabled()) {
            hideAll();
            return;
        }
        List<String> steps = steps();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.store().get(p);
            if (!d.tutorial.equals("actif") || steps.isEmpty()) {
                hide(p);
                continue;
            }
            // Une étape à la fois, pour que chaque réussite se remarque
            for (String id : steps) {
                if (!d.steps.contains(id) && isDone(p, d, id)) {
                    complete(p, d, id);
                    break;
                }
            }
            int done = 0;
            String next = null;
            for (String id : steps) {
                if (d.steps.contains(id)) done++;
                else if (next == null) next = id;
            }
            if (next == null) {
                finish(p, d);
                continue;
            }
            if (d.hideBar) {
                hide(p);
                continue;
            }
            Component name = plugin.parse("<#e8c872><bold>Premiers pas</bold></#e8c872> <dark_gray>" + (done + 1) + "/" + steps.size()
                    + "</dark_gray>  <white>" + text(next));
            float progress = Math.max(0f, Math.min(1f, done / (float) steps.size()));
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(name);
                bar.progress(progress);
            }
        }
    }

    private void complete(Player p, PlayerData d, String id) {
        d.steps.add(id);
        String path = "accueil.etapes." + id + ".recompense";
        plugin.give(p, path);
        if (id.equals("survie") && !d.kitGiven) {
            d.kitGiven = true;
            giveBook(p);
            if (griefPrevention() != null) p.getInventory().addItem(new ItemStack(Material.GOLDEN_SHOVEL));
        }
        p.sendMessage(plugin.lyon().prefixed("<green>✔</green> <white>" + text(id) + "</white> <dark_gray>·</dark_gray> ").append(plugin.describe(path)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
    }

    private void finish(Player p, PlayerData d) {
        d.tutorial = "fini";
        hide(p);
        plugin.give(p, "accueil.fin.recompense");
        Bukkit.broadcast(plugin.parse(plugin.getConfig().getString("accueil.fin.annonce", ""), Placeholder.unparsed("player", p.getName())));
        p.showTitle(Title.title(plugin.parse("<#e8c872><bold>Premiers pas terminés !"), plugin.describe("accueil.fin.recompense"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    private List<Component> guidePages() {
        ConfigurationSection s = plugin.section("accueil.livre");
        List<Component> pages = new ArrayList<>();
        if (s != null) for (String page : s.getStringList("pages")) pages.add(plugin.parse(page.replace("\\n", "\n")));
        return pages;
    }

    /** Ouvre le guide à l'écran (sans objet), par exemple à la toute première connexion. */
    void openGuide(Player p) {
        ConfigurationSection s = plugin.section("accueil.livre");
        List<Component> pages = guidePages();
        if (s == null || pages.isEmpty()) return;
        p.openBook(net.kyori.adventure.inventory.Book.book(Component.text(s.getString("titre", "Guide de LyonMC")), Component.text("LyonMC"), pages));
    }

    private void giveBook(Player p) {
        ConfigurationSection s = plugin.section("accueil.livre");
        if (s == null) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (book.getItemMeta() instanceof BookMeta meta) {
            meta.title(Component.text(s.getString("titre", "Guide de LyonMC")));
            meta.author(Component.text("wassdead"));
            meta.pages(guidePages());
            book.setItemMeta(meta);
        }
        for (ItemStack left : p.getInventory().addItem(book).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private void hide(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    void hideAll() {
        for (Player p : Bukkit.getOnlinePlayers()) hide(p);
        bars.clear();
    }

    void reset(Player p) {
        PlayerData d = plugin.store().get(p);
        d.tutorial = "actif";
        d.steps.clear();
        d.saidHello = false;
        d.visitedMine = false;
        d.kitGiven = false;
        d.hideBar = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) {
        PlayerData d = plugin.store().get(e.getPlayer().getUniqueId());
        if (d != null) d.saidHello = true;
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        if (plugin.isMine(e.getPlayer().getWorld())) plugin.store().get(e.getPlayer()).visitedMine = true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        bars.remove(e.getPlayer().getUniqueId());
    }

    // ───────────────────────────── Commande /premierspas ─────────────────────────────

    void command(Player p, String[] args) {
        PlayerData d = plugin.store().get(p);
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        if (sub.equals("cacher") || sub.equals("afficher")) {
            d.hideBar = sub.equals("cacher");
            plugin.msg(p, d.hideBar ? "<gray>Barre des premiers pas cachée. <white>/premierspas afficher</white> pour la revoir."
                    : "<gray>Barre des premiers pas affichée.");
            return;
        }
        if (sub.equals("commencer") && d.tutorial.equals("ignore")) {
            d.tutorial = "actif";
            plugin.msg(p, "<gray>C'est parti : suis les étapes en haut de l'écran !");
            return;
        }
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#e8c872>✦</#e8c872> <white><bold>Premiers pas</bold></white>"
                + (d.tutorial.equals("fini") ? " <green>terminés !</green>" : "")));
        if (d.tutorial.equals("ignore")) {
            p.sendMessage(plugin.parse("  <gray>Tu étais déjà là avant : les premiers pas sont faits pour les nouveaux."));
            p.sendMessage(plugin.parse("  <gray>Envie de les faire quand même ? <white><click:run_command:'/premierspas commencer'><u>/premierspas commencer</u></click>"));
            return;
        }
        for (String id : steps()) {
            boolean ok = d.steps.contains(id);
            p.sendMessage(plugin.parse("  " + (ok ? "<green>✔</green> <dark_gray><st>" : "<gray>✘</gray> <white>") + text(id)
                    + (ok ? "</st>" : "") + " <dark_gray>· </dark_gray>").append(plugin.describe("accueil.etapes." + id + ".recompense")));
        }
        p.sendMessage(plugin.parse("  <gray>À la fin : ").append(plugin.describe("accueil.fin.recompense")));
        if (d.tutorial.equals("actif")) {
            p.sendMessage(plugin.parse("  <dark_gray>" + (d.hideBar ? "/premierspas afficher : revoir la barre" : "/premierspas cacher : masquer la barre")));
        }
        p.sendMessage(Component.empty());
    }

    // ───────────────────────────── Bienvenue ─────────────────────────────

    void onJoin(Player p, boolean fresh) {
        if (!fresh || !enabled() || !plugin.getConfig().getBoolean("accueil.bienvenue.enabled", true)) return;
        long until = System.currentTimeMillis() + plugin.getConfig().getLong("accueil.bienvenue.minutes", 5) * 60_000L;
        newcomers.removeIf(n -> n.until() < System.currentTimeMillis());
        newcomers.add(new Newcomer(p.getUniqueId(), p.getName(), until, plugin.store().get(p).ipHash, ConcurrentHashMap.newKeySet()));
        Component line = plugin.parse("<#e8c872>✦</#e8c872> <white>" + p.getName() + "</white> <gray>arrive sur LyonMC pour la première fois ! "
                + "<click:run_command:'/bienvenue'><hover:show_text:'<gray>Lui souhaiter la bienvenue (et gagner des pièces)'>"
                + "<#9be37c>[Souhaiter la bienvenue]</#9be37c></hover></click>");
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.sendMessage(line);
        p.sendMessage(plugin.lyon().prefixed("<gray>Bienvenue ! Suis les <#e8c872>premiers pas</#e8c872> en haut de l'écran : chaque étape te rapporte des pièces."));
        p.sendMessage(plugin.lyon().prefixed("<gray>Une question ? Tout est expliqué dans <white><click:run_command:'/aide'><u>/aide</u></click></white>."));
        // Le guide s'ouvre tout seul quelques secondes après l'arrivée
        if (plugin.getConfig().getBoolean("accueil.livre.ouvrir-a-la-premiere-connexion", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) openGuide(p);
            }, 80L);
        }
    }

    void welcomeCommand(Player p) {
        long now = System.currentTimeMillis();
        newcomers.removeIf(n -> n.until() < now);
        PlayerData me = plugin.store().get(p);
        Newcomer target = null;
        for (int i = newcomers.size() - 1; i >= 0; i--) {
            Newcomer n = newcomers.get(i);
            if (!n.uuid().equals(p.getUniqueId()) && !n.welcomedBy().contains(p.getUniqueId())) {
                target = n;
                break;
            }
        }
        if (target == null) {
            plugin.msg(p, "<gray>Personne n'est arrivé ces dernières minutes (ou tu leur as déjà souhaité la bienvenue).");
            return;
        }
        boolean sameIp = target.ip() != null && target.ip().equals(me.ipHash);
        target.welcomedBy().add(p.getUniqueId());
        Bukkit.broadcast(plugin.parse("<#9be37c>✦</#9be37c> <white>" + p.getName() + "</white> <gray>souhaite la bienvenue à <white>" + target.name() + "</white> !"));
        if (sameIp && !plugin.sameIpAllowed()) return; // pas de récompense entre comptes d'une même connexion
        if (target.welcomedBy().size() > 15) return;
        plugin.give(p, "accueil.bienvenue.recompense");
        p.sendActionBar(plugin.parse("<gray>Merci pour l'accueil ! ").append(plugin.describe("accueil.bienvenue.recompense")));
    }

    /** Nombre de joueurs suivant les premiers pas (pour l'export du site). */
    int activeLearners() {
        int n = 0;
        for (PlayerData d : plugin.store().all()) if (d.tutorial.equals("actif")) n++;
        return n;
    }

    Set<String> visibleSteps() {
        return new HashSet<>(steps());
    }
}
