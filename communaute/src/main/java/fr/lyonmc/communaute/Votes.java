package fr.lyonmc.communaute;

import fr.lyonmc.achievements.core.Profile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Votes : récompense à chaque vote (via NuVotifier), série, vote party, top du mois, rappels, /vote.
 */
public final class Votes implements Listener {

    public record Site(String name, String url) {
    }

    private final Communaute plugin;
    /** Premier rappel général 2 h après le démarrage (chaque joueur a déjà le sien à sa connexion). */
    private long lastReminder = System.currentTimeMillis();

    Votes(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("votes.enabled", true);
    }

    public List<Site> sites() {
        List<Site> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("votes.sites")) {
            Object n = m.get("nom"), l = m.get("lien");
            if (n != null && l != null) out.add(new Site(String.valueOf(n), String.valueOf(l)));
        }
        return out;
    }

    // ───────────────────────────── NuVotifier ─────────────────────────────

    /** Écoute les votes de NuVotifier (sans dépendre de sa version : lecture par réflexion). */
    @SuppressWarnings("unchecked")
    void hookVotifier() {
        Plugin votifier = Bukkit.getPluginManager().getPlugin("Votifier");
        if (votifier == null) {
            plugin.getLogger().info("NuVotifier n'est pas installé : les votes ne seront pas reçus automatiquement.");
            return;
        }
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true,
                    votifier.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Object vote = event.getClass().getMethod("getVote").invoke(event);
                    String user = String.valueOf(vote.getClass().getMethod("getUsername").invoke(vote));
                    String service = String.valueOf(vote.getClass().getMethod("getServiceName").invoke(vote));
                    if (Bukkit.isPrimaryThread()) handleVote(user, service);
                    else Bukkit.getScheduler().runTask(plugin, () -> handleVote(user, service));
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("Vote illisible : " + e.getMessage());
                }
            }, plugin);
            plugin.getLogger().info("Votes branchés sur NuVotifier.");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("NuVotifier trouvé mais sa version n'est pas reconnue : votes non branchés.");
        }
    }

    // ───────────────────────────── Un vote ─────────────────────────────

    void handleVote(String username, String service) {
        if (!enabled() || username == null || username.isBlank()) return;
        Player online = Bukkit.getPlayerExact(username);
        UUID uuid;
        String name;
        if (online != null) {
            uuid = online.getUniqueId();
            name = online.getName();
        } else {
            Profile pr = plugin.lyon().profiles().byName(username);
            if (pr == null) {
                plugin.getLogger().info("Vote de " + username + " ignoré : ce joueur n'est jamais venu sur le serveur.");
                return;
            }
            uuid = pr.uuid();
            name = pr.name;
        }
        PlayerData d = plugin.store().getOrCreate(uuid, name);
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(plugin.zone());
        LocalDate last = d.lastVote == 0 ? null : Instant.ofEpochMilli(d.lastVote).atZone(plugin.zone()).toLocalDate();
        if (last == null || last.isBefore(today.minusDays(1))) d.streak = 1;
        else if (last.equals(today.minusDays(1))) d.streak++;
        // même jour : la série ne bouge pas
        d.votes++;
        String month = plugin.month();
        if (!month.equals(d.voteMonth)) {
            d.voteMonth = month;
            d.monthVotes = 0;
        }
        d.monthVotes++;
        d.lastVote = now;

        int every = plugin.getConfig().getInt("votes.serie.jours", 7);
        boolean streakBonus = every > 0 && d.streak > 0 && d.streak % every == 0 && !last(today, last);
        if (online != null) {
            reward(online, d, streakBonus);
        } else {
            d.pendingVotes++;
            if (streakBonus) d.pending.add("votes.serie.recompense");
        }
        Bukkit.broadcast(plugin.parse(plugin.getConfig().getString("votes.annonce", ""),
                Placeholder.unparsed("player", name), Placeholder.unparsed("site", service == null ? "?" : service)));
        plugin.goals().add(uuid, name, "VOTES", 1);

        // Vote party
        int target = plugin.getConfig().getInt("votes.vote-party.votes", 50);
        int count = plugin.store().state().getInt("vote-party", 0) + 1;
        if (target > 0 && count >= target) {
            count = 0;
            party();
        }
        plugin.store().state().set("vote-party", count);
    }

    /** Vrai si le joueur avait déjà voté aujourd'hui (la série ne se compte qu'une fois par jour). */
    private static boolean last(LocalDate today, LocalDate last) {
        return last != null && last.equals(today);
    }

    private void reward(Player p, PlayerData d, boolean streakBonus) {
        plugin.give(p, "votes.recompense");
        p.sendMessage(plugin.lyon().prefixed("<#9be37c>Merci pour ton vote !</#9be37c> ").append(plugin.describe("votes.recompense")));
        if (streakBonus) {
            plugin.give(p, "votes.serie.recompense");
            p.sendMessage(plugin.lyon().prefixed("<gray>Série de <white>" + d.streak + "</white> jours de vote : ").append(plugin.describe("votes.serie.recompense")));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
    }

    private void party() {
        Bukkit.broadcast(plugin.parse("<#9be37c><bold>✦ VOTE PARTY !</bold></#9be37c> <gray>Le serveur a atteint son objectif de votes : cadeau pour tous les joueurs connectés !"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.give(p, "votes.vote-party.recompense");
            p.showTitle(Title.title(plugin.parse("<#9be37c><bold>Vote party !"), plugin.describe("votes.vote-party.recompense"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);
        }
        int minutes = plugin.getConfig().getInt("votes.vote-party.bonus-minutes", 30);
        if (minutes > 0) plugin.happyHours().start("vote-party", minutes * 60_000L);
        plugin.lyon().discord().send("Vote party !", "Le serveur a atteint son objectif de votes : cadeau pour tous les joueurs connectés"
                + (minutes > 0 ? " et bonus x2 pendant " + minutes + " minutes." : "."), 0x9BE37C, null);
    }

    // ───────────────────────────── Connexion, rappels, mois ─────────────────────────────

    void onJoin(Player p) {
        if (!enabled()) return;
        PlayerData d = plugin.store().get(p);
        if (d.pendingVotes > 0) {
            int n = d.pendingVotes;
            d.pendingVotes = 0;
            for (int i = 0; i < n; i++) plugin.give(p, "votes.recompense");
            p.sendMessage(plugin.lyon().prefixed("<#9be37c>Merci pour tes " + n + " vote" + (n > 1 ? "s" : "") + " !</#9be37c> <gray>Récompense"
                    + (n > 1 ? " (x" + n + ")" : "") + " : ").append(plugin.describe("votes.recompense")));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) remind(p);
        }, 300L);
    }

    private void remind(Player p) {
        if (sites().isEmpty()) return;
        PlayerData d = plugin.store().get(p);
        long hours = plugin.getConfig().getLong("votes.rappel-heures", 24);
        if (System.currentTimeMillis() - d.lastVote < hours * 3_600_000L) return;
        p.sendMessage(plugin.lyon().prefixed("<gray>Tu n'as pas encore voté aujourd'hui : chaque vote te rapporte ")
                .append(plugin.describe("votes.recompense"))
                .append(plugin.parse(" <click:run_command:'/vote'><hover:show_text:'<gray>Voir les liens de vote'><#9be37c><u>/vote</u></#9be37c></hover></click>")));
    }

    /** Chaque minute : rappels (toutes les 2 h) et récompenses du mois. */
    void tick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastReminder > 2 * 3_600_000L) {
            lastReminder = now;
            for (Player p : Bukkit.getOnlinePlayers()) remind(p);
        }
        String month = plugin.month();
        String stored = plugin.store().state().getString("votes.mois");
        if (stored == null) {
            plugin.store().state().set("votes.mois", month);
        } else if (!stored.equals(month)) {
            rewardMonth(stored);
            plugin.store().state().set("votes.mois", month);
            plugin.store().saveState();
        }
    }

    private void rewardMonth(String month) {
        List<PlayerData> top = top(month, 3);
        if (top.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            PlayerData d = top.get(i);
            String path = "votes.top-mois." + (i + 1);
            if (plugin.section(path) != null) plugin.giveOrQueue(d.uuid, d.name, path);
            sb.append(i + 1).append(". ").append(d.name).append(" (").append(d.votesIn(month)).append(" votes)  ");
        }
        Bukkit.broadcast(plugin.parse("<#9be37c>✦</#9be37c> <gray>Meilleurs voteurs du mois : <white>" + sb.toString().trim() + "</white>. Merci !"));
        plugin.lyon().discord().send("Meilleurs voteurs du mois", sb.toString().trim(), 0x9BE37C, top.get(0).uuid);
    }

    public List<PlayerData> top(String month, int n) {
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData d : plugin.store().all()) if (d.votesIn(month) > 0) list.add(d);
        list.sort(Comparator.comparingInt((PlayerData d) -> d.votesIn(month)).reversed());
        return list.size() > n ? list.subList(0, n) : list;
    }

    public int partyCount() {
        return plugin.store().state().getInt("vote-party", 0);
    }

    public int partyTarget() {
        return plugin.getConfig().getInt("votes.vote-party.votes", 50);
    }

    // ───────────────────────────── /vote ─────────────────────────────

    void command(Player p) {
        PlayerData d = plugin.store().get(p);
        String month = plugin.month();
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#9be37c>✦</#9be37c> <white><bold>Voter pour LyonMC</bold></white>"));
        p.sendMessage(plugin.parse("  <gray>Chaque vote fait monter le serveur dans les classements et te rapporte ").append(plugin.describe("votes.recompense")));
        List<Site> sites = sites();
        if (sites.isEmpty()) {
            p.sendMessage(plugin.parse("  <dark_gray>Les liens de vote arrivent bientôt."));
        } else {
            for (Site s : sites) {
                p.sendMessage(plugin.parse("  <#9be37c>➤</#9be37c> <click:open_url:'" + s.url().replace("'", "") + "'><hover:show_text:'<gray>Ouvrir la page de vote'><white><u>"
                        + s.name().replace("<", "") + "</u></white></hover></click>"));
            }
        }
        p.sendMessage(plugin.parse("  <gray>Tes votes ce mois : <white>" + d.votesIn(month) + "</white>   <gray>série : <white>" + d.streak
                + "</white> <gray>jour(s)   <gray>vote party : <white>" + partyCount() + "</white><dark_gray>/" + partyTarget()));
        ConfigurationSection first = plugin.section("votes.top-mois.1");
        if (first != null) p.sendMessage(plugin.parse("  <gray>1er voteur du mois : ").append(plugin.describe("votes.top-mois.1")));
        p.sendMessage(Component.empty());
    }
}
