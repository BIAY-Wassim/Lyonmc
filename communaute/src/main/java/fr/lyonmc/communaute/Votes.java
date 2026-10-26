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

    /**
     * Un site de vote : nom, lien de vote et, s'il n'envoie pas les votes par NuVotifier, l'adresse qui vérifie
     * un vote (par IP ou par pseudo) et la façon de lire sa réponse.
     */
    public record Site(String name, String url, String check, VoteSites.Answer answer) {
        boolean byName() {
            return check != null && check.contains("{pseudo}");
        }
    }

    /** Vérification en attente : joueur, site, adresse IP. */
    private record Check(UUID uuid, String name, Site site, String ip) {
    }

    private final java.util.ArrayDeque<Check> queue = new java.util.ArrayDeque<>();
    /** Joueurs qui ont ouvert /vote récemment (on vérifie leurs votes pendant 15 minutes). */
    private final Map<UUID, Long> watching = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> lastManual = new java.util.concurrent.ConcurrentHashMap<>();
    private java.net.http.HttpClient http;

    private final Communaute plugin;
    /** Premier rappel général 2 h après le démarrage (chaque joueur a déjà le sien à sa connexion). */
    private long lastReminder = System.currentTimeMillis();

    Votes(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("votes.enabled", true);
    }

    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Résumé dans la console : quels sites sont vérifiés tout seuls. */
    void logSites() {
        warned.clear();
        List<String> checked = new ArrayList<>(), votifier = new ArrayList<>();
        for (Site s : sites()) (s.check() != null ? checked : votifier).add(s.name());
        if (!checked.isEmpty()) plugin.getLogger().info("Votes vérifiés directement auprès de : " + String.join(", ", checked));
        if (!votifier.isEmpty()) plugin.getLogger().info("Votes attendus par NuVotifier pour : " + String.join(", ", votifier));
    }

    public List<Site> sites() {
        List<Site> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("votes.sites")) {
            Object l = m.get("lien");
            if (l == null) continue;
            String link = String.valueOf(l).trim();
            VoteSites.Provider prov = VoteSites.find(link);
            Object n = m.get("nom");
            String name = n != null ? String.valueOf(n) : prov != null ? prov.domain() : link;
            Object v = m.get("verification"), key = m.get("cle"), off = m.get("verifier");
            String check = null;
            VoteSites.Answer answer = null;
            if (off != null && !Boolean.parseBoolean(String.valueOf(off))) {
                check = null; // votes reçus par NuVotifier seulement
            } else if (v != null && !String.valueOf(v).isBlank()) {
                check = String.valueOf(v);
            } else if (prov != null) {
                String id = key != null && !String.valueOf(key).isBlank() ? String.valueOf(key).trim() : VoteSites.idFromLink(prov, link);
                if (id != null) {
                    check = prov.url().replace("{id}", java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8));
                    answer = prov.answer();
                } else if (prov.keyLabel() != null && warned.add(name)) {
                    plugin.getLogger().info("Votes : pour vérifier les votes sur " + prov.domain() + ", ajoute « cle: » avec " + prov.keyLabel()
                            + " (ou utilise NuVotifier si le site le propose).");
                }
            }
            out.add(new Site(name, link, check, answer));
        }
        return out;
    }

    // ───────────────────────────── Vérification par IP (sites sans NuVotifier) ─────────────────────────────

    private boolean hasChecks() {
        for (Site s : sites()) if (s.check() != null) return true;
        return false;
    }

    private static String ip(Player p) {
        return p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
    }

    /** Met en file les vérifications de ce joueur (une par site qui vérifie par IP). */
    int queueChecks(Player p) {
        String ip = ip(p);
        if (ip == null) return 0;
        int added = 0;
        synchronized (queue) {
            for (Site s : sites()) {
                if (s.check() == null) continue;
                boolean already = queue.stream().anyMatch(c -> c.uuid().equals(p.getUniqueId()) && c.site().name().equals(s.name()));
                if (!already) {
                    queue.add(new Check(p.getUniqueId(), p.getName(), s, ip));
                    added++;
                }
            }
        }
        return added;
    }

    /** Vérifications demandées avec « Vérifier » : restantes, votes récompensés, votes déjà récompensés (sites). */
    private final Map<UUID, Object[]> manual = new java.util.concurrent.ConcurrentHashMap<>();

    /** Résultat d'une vérification : 0 = pas de vote, 1 = vote récompensé, 2 = vote déjà récompensé. */
    private void manualResult(Check c, int outcome) {
        Object[] m = manual.get(c.uuid());
        if (m == null) return;
        if (outcome == 1) m[1] = (int) m[1] + 1;
        if (outcome == 2) ((List<String>) m[2]).add(c.site().name());
        m[0] = (int) m[0] - 1;
        if ((int) m[0] > 0) return;
        manual.remove(c.uuid());
        Player p = Bukkit.getPlayer(c.uuid());
        if (p == null || (int) m[1] > 0) return;
        @SuppressWarnings("unchecked") List<String> done = (List<String>) m[2];
        if (!done.isEmpty()) {
            p.sendMessage(plugin.lyon().prefixed("<gray>Ton vote sur <white>" + String.join(", ", done)
                    + "</white> a déjà été récompensé. Tu pourras revoter quand le site le permettra."));
        } else {
            p.sendMessage(plugin.lyon().prefixed("<gray>Aucun nouveau vote trouvé pour l'instant. Clique sur un site dans <white>/vote</white>, "
                    + "vote sur sa page jusqu'au message de confirmation, puis reviens : la récompense arrive toute seule."));
        }
    }

    /** Toutes les 1,5 s : une seule requête (les sites demandent au plus une requête par seconde). */
    void tickChecks() {
        if (!enabled()) return;
        Check c;
        synchronized (queue) {
            c = queue.poll();
        }
        if (c == null) return;
        if (http == null) http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        String url = c.site().check().replace("{ip}", java.net.URLEncoder.encode(c.ip(), java.nio.charset.StandardCharsets.UTF_8))
                .replace("{pseudo}", java.net.URLEncoder.encode(c.name(), java.nio.charset.StandardCharsets.UTF_8));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "LyonMC-COMMUNAUTE").GET().build();
        boolean debug = plugin.getConfig().getBoolean("votes.debug", false);
        http.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            try {
                if (err != null || res == null) {
                    if (debug) plugin.getLogger().info("[Votes] " + c.site().name() + " injoignable : " + err);
                    Bukkit.getScheduler().runTask(plugin, () -> manualResult(c, 0));
                    return;
                }
                int status = res.statusCode();
                String body = res.body() == null ? "" : res.body().trim();
                if ((status == 401 || status == 403) && warned.add("cle:" + c.site().name())) {
                    plugin.getLogger().warning("Votes : " + c.site().name() + " refuse la vérification (code " + status + ") : vérifie la clé « cle: » dans config.yml.");
                }
                VoteSites.Result r = VoteSites.read(c.site().answer(), status, body);
                if (debug) plugin.getLogger().info("[Votes] " + c.site().name() + " pour " + c.name() + " : code " + status + " → " + r
                        + " (" + (body.length() > 160 ? body.substring(0, 160) + "…" : body) + ")");
                Bukkit.getScheduler().runTask(plugin, () -> checked(c, r, body));
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Vérification du vote sur " + c.site().name() + " impossible", t);
            }
        });
    }

    /**
     * Réponse d'un site. Un vote ne compte qu'une fois, même si plusieurs joueurs partagent l'IP :
     * si le site donne la date du vote, chaque date ne compte qu'une fois ; sinon on compte quand le site passe
     * de « pas voté » à « a voté » (et au plus une fois par 24 h s'il répond « a voté » sans arrêt).
     */
    private void checked(Check c, VoteSites.Result r, String body) {
        manualResult(c, checkedOutcome(c, r, body));
    }

    private int checkedOutcome(Check c, VoteSites.Result r, String body) {
        if (r == VoteSites.Result.UNKNOWN) return 0;
        String who = c.site().byName() ? "pseudo:" + c.name().toLowerCase(java.util.Locale.ROOT) : c.ip();
        String stateKey = "votes.etat." + Integer.toHexString((c.site().name() + "|" + who).hashCode()).replace('-', 'n');
        var state = plugin.store().state();
        if (r == VoteSites.Result.NOT_VOTED) {
            if (state.getBoolean(stateKey + ".a-vote", false)) {
                state.set(stateKey + ".a-vote", false);
                plugin.store().saveState();
            }
            return 0;
        }
        long now = System.currentTimeMillis();
        String date = VoteSites.voteDate(body);
        if (date != null) {
            String key = c.site().name() + "|" + who + "|" + date;
            List<String> seen = new ArrayList<>(state.getStringList("votes.ip-recus"));
            if (seen.contains(key)) return 2;
            seen.add(key);
            if (seen.size() > 1000) seen = new ArrayList<>(seen.subList(seen.size() - 1000, seen.size()));
            state.set("votes.ip-recus", seen);
        } else {
            boolean was = state.getBoolean(stateKey + ".a-vote", false);
            long lastReward = state.getLong(stateKey + ".recompense", 0);
            if (was && now - lastReward < 24 * 3_600_000L) return 2;
        }
        state.set(stateKey + ".a-vote", true);
        state.set(stateKey + ".recompense", now);
        plugin.store().saveState();
        handleVote(c.name(), c.site().name());
        return 1;
    }

    /** Toutes les 30 s : on revérifie les joueurs qui ont ouvert /vote il y a moins de 15 minutes. */
    void tickWatch() {
        long now = System.currentTimeMillis();
        watching.entrySet().removeIf(e -> now - e.getValue() > 15 * 60_000L);
        for (UUID u : watching.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) queueChecks(p);
        }
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
        if (hasChecks()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) queueChecks(p);
            }, 60L);
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

    void command(Player p, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("verifier")) {
            long now = System.currentTimeMillis();
            Long last = lastManual.get(p.getUniqueId());
            if (last != null && now - last < 10_000L) {
                p.sendMessage(plugin.lyon().prefixed("<gray>Vérification déjà en cours, patiente quelques secondes."));
                return;
            }
            lastManual.put(p.getUniqueId(), now);
            watching.put(p.getUniqueId(), now);
            int n = queueChecks(p);
            if (n > 0) manual.put(p.getUniqueId(), new Object[]{n, 0, new ArrayList<String>()});
            p.sendMessage(plugin.lyon().prefixed("<gray>Je demande aux sites de vote si tu as voté… <dark_gray>(quelques secondes)"));
            return;
        }
        if (hasChecks()) watching.put(p.getUniqueId(), System.currentTimeMillis());
        PlayerData d = plugin.store().get(p);
        String month = plugin.month();
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#9be37c>✦</#9be37c> <white><bold>Voter pour LyonMC</bold></white>"));
        p.sendMessage(plugin.parse("  <gray>Chaque vote fait monter le serveur dans les classements et te rapporte ").append(plugin.describe("votes.recompense")));
        List<Site> sites = sites();
        if (!sites.isEmpty()) {
            p.sendMessage(plugin.parse("  <white>1.</white> <gray>Clique sur un site : sa page de vote s'ouvre dans ton navigateur."));
            p.sendMessage(plugin.parse("  <white>2.</white> <gray>Vote sur la page (ton pseudo, le captcha, puis « Voter »)."));
            p.sendMessage(plugin.parse("  <white>3.</white> <gray>Reviens en jeu : ta récompense arrive toute seule."));
        }
        if (sites.isEmpty()) {
            p.sendMessage(plugin.parse("  <dark_gray>Les liens de vote arrivent bientôt."));
        } else {
            for (Site s : sites) {
                p.sendMessage(plugin.parse("  <#9be37c>➤</#9be37c> <click:open_url:'" + s.url().replace("'", "") + "'><hover:show_text:'<gray>Ouvrir la page de vote de "
                        + s.name().replace("<", "").replace("'", "") + "'><white><u>" + s.name().replace("<", "") + "</u></white></hover></click>"
                        + (s.check() != null ? " <click:run_command:'/vote verifier'><hover:show_text:'<gray>Tu as voté mais rien reçu ?\n<gray>Clique pour vérifier tout de suite.'>"
                        + "<dark_gray>[</dark_gray><gray>Vérifier</gray><dark_gray>]</dark_gray></hover></click>" : "")));
            }
        }
        p.sendMessage(plugin.parse("  <gray>Tes votes ce mois : <white>" + d.votesIn(month) + "</white>   <gray>série : <white>" + d.streak
                + "</white> <gray>jour(s)   <gray>vote party : <white>" + partyCount() + "</white><dark_gray>/" + partyTarget()));
        ConfigurationSection first = plugin.section("votes.top-mois.1");
        if (first != null) p.sendMessage(plugin.parse("  <gray>1er voteur du mois : ").append(plugin.describe("votes.top-mois.1")));
        p.sendMessage(Component.empty());
    }
}
