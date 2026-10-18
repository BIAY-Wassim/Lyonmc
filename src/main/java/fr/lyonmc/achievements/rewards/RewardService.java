package fr.lyonmc.achievements.rewards;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.util.Text;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * Titres, cosmétiques (couleurs de pseudo, traînées), parcours de paliers et attribution des récompenses.
 */
public final class RewardService {

    public record TitleDef(String id, String display, String description, boolean byDefault, long price) {
    }

    public record CosmeticDef(String id, String kind, String display, String value, Material icon, long price, Particle particle) {
    }

    public record Condition(String type, String arg, double value) {
    }

    public record Palier(String id, String name, Material icon, Condition condition, Bundle reward, boolean announce) {
    }

    private final LyonAchievements plugin;
    private final Map<String, TitleDef> titles = new LinkedHashMap<>();
    private final Map<String, CosmeticDef> colors = new LinkedHashMap<>();
    private final Map<String, CosmeticDef> trails = new LinkedHashMap<>();
    private final List<Palier> paliers = new ArrayList<>();
    private BukkitTask trailTask;
    private final Map<UUID, Location> lastPos = new HashMap<>();

    public RewardService(LyonAchievements plugin) {
        this.plugin = plugin;
        plugin.text().addProvider((b, uuid) -> {
            Profile p = uuid == null ? null : plugin.profiles().get(uuid);
            b.resolver(Placeholder.component("title", titleTag(p)));
            b.resolver(Placeholder.component("title_raw", titleRaw(p)));
        });
    }

    // ───────────────────────────── Chargement ─────────────────────────────

    public void load() {
        ConfigurationSection cfg = plugin.configs().rewards();
        titles.clear();
        colors.clear();
        trails.clear();
        paliers.clear();

        ConfigurationSection t = cfg.getConfigurationSection("titles");
        if (t != null) {
            for (String id : t.getKeys(false)) {
                ConfigurationSection s = t.getConfigurationSection(id);
                if (s == null) continue;
                titles.put(id, new TitleDef(id, s.getString("display", id), s.getString("description", ""),
                        s.getBoolean("default", false), s.getLong("price", 0)));
            }
        }
        ConfigurationSection c = cfg.getConfigurationSection("colors");
        if (c != null) {
            for (String id : c.getKeys(false)) {
                ConfigurationSection s = c.getConfigurationSection(id);
                if (s == null) continue;
                colors.put(id, new CosmeticDef(id, "color", s.getString("display", id), s.getString("value", "#ffffff"),
                        Items.material(s.getString("icon"), Material.WHITE_DYE), s.getLong("price", 0), null));
            }
        }
        ConfigurationSection tr = cfg.getConfigurationSection("trails");
        if (tr != null) {
            for (String id : tr.getKeys(false)) {
                ConfigurationSection s = tr.getConfigurationSection(id);
                if (s == null) continue;
                Particle particle;
                try {
                    particle = Particle.valueOf(s.getString("particle", "HEART").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Particule inconnue pour la traînée " + id);
                    continue;
                }
                if (particle.getDataType() != Void.class) {
                    plugin.getLogger().warning("La particule " + particle + " (traînée " + id + ") demande des données : ignorée.");
                    continue;
                }
                trails.put(id, new CosmeticDef(id, "trail", s.getString("display", id), particle.name(),
                        Items.material(s.getString("icon"), Material.BLAZE_POWDER), s.getLong("price", 0), particle));
            }
        }
        ConfigurationSection p = cfg.getConfigurationSection("paliers");
        if (p != null) {
            for (String id : p.getKeys(false)) {
                ConfigurationSection s = p.getConfigurationSection(id);
                if (s == null) continue;
                ConfigurationSection cond = s.getConfigurationSection("condition");
                if (cond == null) continue;
                paliers.add(new Palier(id, s.getString("name", id), Items.material(s.getString("icon"), Material.CHEST),
                        new Condition(cond.getString("type", "achievements").toLowerCase(Locale.ROOT), cond.getString("arg"),
                                cond.getDouble("value", 1)),
                        Bundle.of(s.getConfigurationSection("reward")), s.getBoolean("announce", false)));
            }
        }
        startTrails();
    }

    public Collection<TitleDef> titles() {
        return titles.values();
    }

    public TitleDef title(String id) {
        return id == null ? null : titles.get(id);
    }

    public Collection<CosmeticDef> colors() {
        return colors.values();
    }

    public Collection<CosmeticDef> trails() {
        return trails.values();
    }

    public CosmeticDef cosmetic(String kind, String id) {
        if (id == null) return null;
        return "color".equals(kind) ? colors.get(id) : trails.get(id);
    }

    public List<Palier> paliers() {
        return paliers;
    }

    // ───────────────────────────── Titres ─────────────────────────────

    public boolean hasTitle(Profile p, String id) {
        TitleDef def = titles.get(id);
        return def != null && (def.byDefault() || p.titles.contains(id));
    }

    public int unlockedTitles(Profile p) {
        int n = 0;
        for (TitleDef d : titles.values()) if (hasTitle(p, d.id())) n++;
        return n;
    }

    /** "[Titre] " ou vide */
    public Component titleTag(Profile p) {
        Component raw = titleRaw(p);
        if (raw.equals(Component.empty())) return Component.empty();
        return Component.text("[", Text.DARK).append(raw).append(Component.text("] ", Text.DARK));
    }

    public Component titleRaw(Profile p) {
        if (p == null || p.title == null) return Component.empty();
        TitleDef def = titles.get(p.title);
        if (def == null || !hasTitle(p, def.id())) return Component.empty();
        return plugin.text().parse(def.display());
    }

    public void selectTitle(Profile p, String id) {
        p.title = id;
        p.dirty();
    }

    // ───────────────────────────── Cosmétiques ─────────────────────────────

    public boolean hasCosmetic(Profile p, String kind, String id) {
        return p.cosmetics.contains(kind + ":" + id);
    }

    public void unlockCosmetic(Profile p, String kind, String id) {
        if (p.cosmetics.add(kind + ":" + id)) p.dirty();
    }

    /** Pseudo coloré selon la couleur choisie. */
    public Component coloredName(UUID uuid, String name, TextColor fallback) {
        Profile p = plugin.profiles().get(uuid);
        CosmeticDef c = p == null || p.color == null ? null : colors.get(p.color);
        if (c == null || !hasCosmetic(p, "color", c.id())) return Component.text(name, fallback);
        String v = c.value();
        if (v.equalsIgnoreCase("rainbow")) return Text.MM.deserialize("<rainbow>" + name + "</rainbow>");
        if (v.startsWith("gradient:")) return Text.MM.deserialize("<gradient:" + v.substring(9) + ">" + name + "</gradient>");
        TextColor tc = TextColor.fromHexString(v);
        return Component.text(name, tc == null ? fallback : tc);
    }

    /** Aperçu d'une couleur appliquée à un texte. */
    public Component colorPreview(CosmeticDef c, String text) {
        String v = c.value();
        if (v.equalsIgnoreCase("rainbow")) return Text.MM.deserialize("<rainbow>" + text + "</rainbow>");
        if (v.startsWith("gradient:")) return Text.MM.deserialize("<gradient:" + v.substring(9) + ">" + text + "</gradient>");
        TextColor tc = TextColor.fromHexString(v);
        return Component.text(text, tc == null ? Text.WHITE : tc);
    }

    private void startTrails() {
        if (trailTask != null) trailTask.cancel();
        trailTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Profile p = plugin.profiles().get(player.getUniqueId());
                if (p == null || p.trail == null) continue;
                CosmeticDef def = trails.get(p.trail);
                if (def == null || !hasCosmetic(p, "trail", def.id())) continue;
                if (player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()) continue;
                Location now = player.getLocation();
                Location before = lastPos.put(player.getUniqueId(), now);
                if (before == null || before.getWorld() != now.getWorld() || before.distanceSquared(now) < 0.01) continue;
                player.getWorld().spawnParticle(def.particle(), now.clone().add(0, 0.15, 0), 2, 0.2, 0.05, 0.2, 0.01);
            }
        }, 20L, 3L);
    }

    public void stop() {
        if (trailTask != null) trailTask.cancel();
    }

    // ───────────────────────────── Paliers ─────────────────────────────

    /** Valeur actuelle d'une condition pour un joueur. */
    public double current(Player player, Profile p, Condition c) {
        PlayerStats s = plugin.stats().get(p.uuid());
        AdvancementRegistry reg = plugin.registry();
        return switch (c.type()) {
            case "achievements" -> s == null ? 0 : s.count();
            case "achievements-percent" -> s == null || reg.total() == 0 ? 0 : s.count() * 100.0 / reg.total();
            case "challenges" -> s == null ? 0 : s.count(AdvancementDisplay.Frame.CHALLENGE);
            case "challenges-percent" -> {
                int tot = reg.total(AdvancementDisplay.Frame.CHALLENGE);
                yield s == null || tot == 0 ? 0 : s.count(AdvancementDisplay.Frame.CHALLENGE) * 100.0 / tot;
            }
            case "category" -> {
                int tot = reg.total(c.arg());
                yield s != null && tot > 0 && s.count(c.arg()) >= tot ? 1 : 0;
            }
            case "missions" -> p.missionsDone;
            case "mission-streak" -> Math.max(p.missionStreak, p.bestMissionStreak);
            case "login-streak" -> Math.max(p.loginStreak, p.bestLoginStreak);
            case "playtime-hours" -> player == null ? 0 : player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
            case "boss-kills" -> p.bossKills;
            case "coins-earned" -> p.earned;
            case "titles" -> unlockedTitles(p);
            default -> 0;
        };
    }

    /** Texte de la condition, ex : "Atteindre 25 % des succès". */
    public String describe(Condition c) {
        long v = Math.round(c.value());
        return switch (c.type()) {
            case "achievements" -> "Obtenir " + v + " succès";
            case "achievements-percent" -> v >= 100 ? "Obtenir tous les succès" : "Atteindre " + v + " % des succès";
            case "challenges" -> "Réussir " + v + " défis";
            case "challenges-percent" -> v >= 100 ? "Réussir tous les défis" : "Réussir " + v + " % des défis";
            case "category" -> "Terminer la catégorie " + plugin.registry().category(c.arg()).name();
            case "missions" -> "Terminer " + v + " missions";
            case "mission-streak" -> "Série de " + v + " jours de missions";
            case "login-streak" -> "Série de " + v + " récompenses quotidiennes";
            case "playtime-hours" -> "Jouer " + v + " heures";
            case "boss-kills" -> "Vaincre " + v + " fois le boss mondial";
            case "coins-earned" -> "Gagner " + Text.num(v) + " pièces au total";
            case "titles" -> "Débloquer " + v + " titres";
            default -> c.type();
        };
    }

    public double target(Condition c) {
        return c.type().equals("category") ? 1 : c.value();
    }

    public boolean met(Player player, Profile p, Condition c) {
        return current(player, p, c) + 1e-9 >= target(c);
    }

    /** Vérifie et attribue tous les paliers atteints. */
    public void check(Player player) {
        if (player == null || !player.isOnline()) return;
        Profile p = plugin.profiles().get(player);
        for (Palier palier : paliers) {
            if (p.claimed.contains(palier.id())) continue;
            if (!met(player, p, palier.condition())) continue;
            p.claimed.add(palier.id());
            p.dirty();
            grant(player, palier.reward());
            player.showTitle(Title.title(
                    plugin.text().parse("<accent>✦ <white>Palier débloqué</white> ✦"),
                    Component.text(palier.name(), Text.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
            plugin.msg(player, "<accent>✦</accent> <gray>Palier <white>" + palier.name() + "</white> débloqué ! <dark_gray>(/parcours)");
            for (Component line : describe(palier.reward())) {
                player.sendMessage(Component.text("   ", Text.DARK).append(line));
            }
            if (palier.announce()) {
                Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <white>" + player.getName()
                        + "</white> <gray>a débloqué le palier <accent>" + palier.name() + "</accent> !"));
                plugin.discord().send("Palier débloqué", player.getName() + " a débloqué le palier « " + palier.name() + " ».",
                        0xE8C872, player.getUniqueId());
            }
        }
        plugin.custom().checkTitles(player, p);
    }

    // ───────────────────────────── Attribution ─────────────────────────────

    /** Donne un lot à un joueur connecté. */
    public void grant(Player player, Bundle b) {
        if (b == null || b.isEmpty()) return;
        Profile p = plugin.profiles().get(player);
        if (b.coins() > 0) plugin.economy().deposit(player, b.coins());
        grantUnlocks(p, b);
        if (b.homes() > 0) {
            p.bonusHomes += b.homes();
            p.dirty();
        }
        if (b.xpLevels() > 0) player.giveExpLevels(b.xpLevels());
        if (b.xpPoints() > 0) player.giveExp(b.xpPoints());
        for (ItemStack item : b.itemStacks()) {
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        for (String cmd : b.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
        }
        if (b.seasonPoints() > 0) plugin.seasons().addPoints(player.getUniqueId(), player.getName(), b.seasonPoints(), true);
    }

    /** Titres et cosmétiques (fonctionne aussi hors ligne). */
    public void grantUnlocks(Profile p, Bundle b) {
        for (String t : b.titles()) {
            if (titles.containsKey(t) && p.titles.add(t)) {
                if (p.title == null) p.title = t;
                p.dirty();
            }
        }
        if (b.color() != null && colors.containsKey(b.color())) unlockCosmetic(p, "color", b.color());
        if (b.trail() != null && trails.containsKey(b.trail())) unlockCosmetic(p, "trail", b.trail());
    }

    /** Lignes lisibles d'un lot, pour les menus et messages. */
    public List<Component> describe(Bundle b) {
        List<Component> out = new ArrayList<>();
        TextColor accent = plugin.text().accent();
        if (b.coins() > 0) out.add(Component.text("⛁ ", accent).append(Component.text(plugin.economy().format(b.coins()), Text.WHITE)));
        if (b.seasonPoints() > 0) out.add(Component.text("✦ ", accent).append(Component.text(b.seasonPoints() + " points de saison", Text.WHITE)));
        for (String t : b.titles()) {
            TitleDef def = titles.get(t);
            if (def != null) out.add(Component.text("Titre ", Text.GRAY).append(plugin.text().parse(def.display())));
        }
        if (b.color() != null && colors.containsKey(b.color())) {
            CosmeticDef c = colors.get(b.color());
            out.add(Component.text("Couleur ", Text.GRAY).append(colorPreview(c, c.display())));
        }
        if (b.trail() != null && trails.containsKey(b.trail())) {
            out.add(Component.text("Traînée ", Text.GRAY).append(Component.text(trails.get(b.trail()).display(), Text.WHITE)));
        }
        if (b.homes() > 0) out.add(Component.text("+" + b.homes() + " home" + (b.homes() > 1 ? "s" : ""), Text.WHITE));
        if (b.xpLevels() > 0) out.add(Component.text(b.xpLevels() + " niveaux d'expérience", Text.WHITE));
        if (b.xpPoints() > 0) out.add(Component.text(b.xpPoints() + " points d'expérience", Text.WHITE));
        for (ItemStack it : b.itemStacks()) {
            out.add(Component.text(it.getAmount() + "× ", Text.WHITE).append(Component.translatable(it.getType().translationKey()).color(Text.WHITE)));
        }
        return out;
    }

    // ───────────────────────────── Homes ─────────────────────────────

    public int homesLimit(Profile p) {
        return plugin.getConfig().getInt("teleport.homes-base", 2) + p.bonusHomes + p.boughtHomes;
    }
}
