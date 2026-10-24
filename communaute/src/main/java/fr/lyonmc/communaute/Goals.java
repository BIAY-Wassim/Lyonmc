package fr.lyonmc.communaute;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Objectif communautaire de la semaine : tout le serveur avance ensemble,
 * et chaque participant est récompensé quand l'objectif est atteint.
 */
public final class Goals implements Listener {

    private final Communaute plugin;

    Goals(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("objectifs.enabled", true) && !ids().isEmpty();
    }

    private List<String> ids() {
        ConfigurationSection s = plugin.section("objectifs.liste");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private ConfigurationSection current() {
        String id = plugin.store().state().getString("objectif.id");
        return id == null ? null : plugin.section("objectifs.liste." + id);
    }

    public String currentName() {
        ConfigurationSection c = current();
        return c == null ? "aucun" : c.getString("nom", "?");
    }

    public String currentType() {
        ConfigurationSection c = current();
        return c == null ? "" : c.getString("type", "").toUpperCase(Locale.ROOT);
    }

    public int progress() {
        return plugin.store().state().getInt("objectif.progression", 0);
    }

    public int target() {
        ConfigurationSection c = current();
        return c == null ? 0 : Math.max(1, c.getInt("cible", 1));
    }

    public boolean done() {
        return plugin.store().state().getBoolean("objectif.atteint", false);
    }

    private int minContribution() {
        ConfigurationSection c = current();
        return c == null ? 1 : Math.max(1, c.getInt("contribution-min", 1));
    }

    /** Fin de la semaine (lundi prochain, minuit). */
    public long endsAt() {
        ZonedDateTime next = LocalDate.now(plugin.zone()).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(plugin.zone());
        return next.toInstant().toEpochMilli();
    }

    /** Chaque minute : nouvelle semaine, nouvel objectif. */
    void tick() {
        if (!enabled()) return;
        String week = plugin.week();
        if (!week.equals(plugin.store().state().getString("objectif.semaine")) || current() == null) next(false);
    }

    /** Passe à l'objectif suivant (à tour de rôle). */
    void next(boolean forced) {
        List<String> ids = ids();
        if (ids.isEmpty()) return;
        String currentId = plugin.store().state().getString("objectif.id");
        int index = currentId == null ? -1 : ids.indexOf(currentId);
        String id = ids.get((index + 1) % ids.size());
        var st = plugin.store().state();
        st.set("objectif.id", id);
        st.set("objectif.semaine", plugin.week());
        // Clé de cet objectif : la participation de chacun y est rattachée
        st.set("objectif.cle", plugin.week() + "-" + id + (forced ? "-" + System.currentTimeMillis() : ""));
        st.set("objectif.progression", 0);
        st.set("objectif.atteint", false);
        st.set("objectif.paliers", 0);
        plugin.store().saveState();
        Bukkit.broadcast(plugin.parse("<#ffb35c>✦</#ffb35c> <gray>Nouvel objectif de la semaine : <white>" + currentName()
                + "</white> <dark_gray>(/objectif)"));
        plugin.lyon().discord().send("Objectif de la semaine", currentName() + " : tout le serveur ensemble, une récompense pour chaque participant.", 0xFFB35C, null);
    }

    private String weekKey() {
        return plugin.store().state().getString("objectif.cle", plugin.week());
    }

    /** Ajoute une participation (appelé par les évènements et par les votes). */
    void add(UUID uuid, String name, String type, int amount) {
        if (!enabled() || done() || !type.equals(currentType())) return;
        var st = plugin.store().state();
        int before = progress();
        int now = before + amount;
        st.set("objectif.progression", now);
        PlayerData d = plugin.store().getOrCreate(uuid, name);
        String week = weekKey();
        if (!week.equals(d.goalWeek)) {
            d.goalWeek = week;
            d.contribution = 0;
        }
        d.contribution += amount;
        int target = target();
        int step = st.getInt("objectif.paliers", 0);
        int reached = (int) Math.min(3, (long) now * 4 / target);
        if (reached > step && now < target) {
            st.set("objectif.paliers", reached);
            Bukkit.broadcast(plugin.parse("<#ffb35c>✦</#ffb35c> <gray>Objectif de la semaine à <white>" + (reached * 25) + " %</white> : "
                    + currentName() + " <dark_gray>(/objectif)"));
        }
        if (now >= target) complete();
    }

    private void complete() {
        var st = plugin.store().state();
        st.set("objectif.atteint", true);
        plugin.store().saveState();
        String week = weekKey();
        String path = "objectifs.liste." + st.getString("objectif.id") + ".recompense";
        int min = minContribution();
        int rewarded = 0;
        for (PlayerData d : new ArrayList<>(plugin.store().all())) {
            if (d.contributionIn(week) >= min) {
                plugin.giveOrQueue(d.uuid, d.name, path);
                rewarded++;
            }
        }
        Bukkit.broadcast(plugin.parse("<#ffb35c><bold>✦ Objectif atteint !</bold></#ffb35c> <white>" + currentName()
                + "</white> <gray>· " + rewarded + " joueur(s) récompensé(s). Bravo à tous !"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        plugin.lyon().discord().send("Objectif atteint !", currentName() + " : " + rewarded + " joueur(s) récompensé(s).", 0xFFB35C, null);
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    private boolean counts(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL && !plugin.inHub(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!counts(p)) return;
        if (plugin.isMine(p.getWorld())) add(p.getUniqueId(), p.getName(), "MINE", 1);
        if (e.getBlock().getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
            add(p.getUniqueId(), p.getName(), "HARVEST", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null && e.getEntity() instanceof Enemy && counts(killer)) add(killer.getUniqueId(), killer.getName(), "MOBS", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && counts(e.getPlayer())) add(e.getPlayer().getUniqueId(), e.getPlayer().getName(), "FISH", 1);
    }

    // ───────────────────────────── /objectif ─────────────────────────────

    void command(Player p) {
        if (!enabled() || current() == null) {
            plugin.msg(p, "<gray>Pas d'objectif communautaire en ce moment.");
            return;
        }
        int progress = Math.min(progress(), target());
        int pct = (int) ((long) progress * 100 / target());
        int bars = pct / 5;
        String bar = "<#ffb35c>" + "|".repeat(bars) + "</#ffb35c><dark_gray>" + "|".repeat(20 - bars) + "</dark_gray>";
        PlayerData d = plugin.store().get(p);
        int mine = d.contributionIn(weekKey());
        long left = Math.max(0, endsAt() - System.currentTimeMillis());
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#ffb35c>✦</#ffb35c> <white><bold>Objectif de la semaine</bold></white>"));
        p.sendMessage(plugin.parse("  <white>" + currentName()));
        p.sendMessage(plugin.parse("  " + bar + " <white>" + Communaute.num(progress) + "</white><dark_gray>/" + Communaute.num(target()) + " (" + pct + " %)"));
        p.sendMessage(plugin.parse(done() ? "  <green>✔ Atteint ! Les participants ont été récompensés."
                : "  <gray>Fin dans <white>" + (left / 86_400_000L) + " j " + (left / 3_600_000L % 24) + " h"));
        p.sendMessage(plugin.parse("  <gray>Ta participation : <white>" + Communaute.num(mine) + "</white> <dark_gray>(" + minContribution()
                + " minimum pour la récompense)"));
        p.sendMessage(plugin.parse("  <gray>Récompense pour chaque participant : ").append(plugin.describe("objectifs.liste."
                + plugin.store().state().getString("objectif.id") + ".recompense")));
        p.sendMessage(Component.empty());
    }
}
