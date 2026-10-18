package fr.lyonmc.achievements.team;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Équipes : création, invitations, chat d'équipe, home d'équipe, classement. */
public final class TeamService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,16}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{2,4}");

    private final LyonAchievements plugin;
    private final File file;
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, String> memberOf = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> invites = new ConcurrentHashMap<>();
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();

    public TeamService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "equipes.yml");
        plugin.text().addProvider((b, uuid) -> {
            Team t = uuid == null ? null : teamOf(uuid);
            b.resolver(Placeholder.component("team", tagComponent(t)));
            b.resolver(Placeholder.unparsed("team_name", t == null ? "-" : t.name));
        });
    }

    // ───────────────────────────── Données ─────────────────────────────

    public void load() {
        teams.clear();
        memberOf.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("teams");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            try {
                Team t = new Team(id, c.getString("name", id), c.getString("tag", "???"), c.getString("color", "#e8c872"),
                        UUID.fromString(Objects.requireNonNull(c.getString("owner"))));
                for (String m : c.getStringList("members")) t.members.add(UUID.fromString(m));
                t.members.add(t.owner);
                t.created = c.getLong("created", System.currentTimeMillis());
                t.home = StoredLocation.parse(c.getString("home"));
                t.pvp = c.getBoolean("pvp", false);
                teams.put(id, t);
                for (UUID m : t.members) memberOf.put(m, id);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Équipe " + id + " illisible", e);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Team t : teams.values()) {
            String base = "teams." + t.id;
            y.set(base + ".name", t.name);
            y.set(base + ".tag", t.tag);
            y.set(base + ".color", t.color);
            y.set(base + ".owner", t.owner.toString());
            List<String> m = new ArrayList<>();
            for (UUID u : t.members) m.add(u.toString());
            y.set(base + ".members", m);
            y.set(base + ".created", t.created);
            y.set(base + ".home", t.home == null ? null : t.home.serialize());
            y.set(base + ".pvp", t.pvp);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer equipes.yml", e);
        }
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public Team teamOf(UUID uuid) {
        String id = memberOf.get(uuid);
        return id == null ? null : teams.get(id);
    }

    public Team byName(String name) {
        return name == null ? null : teams.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Team> all() {
        return teams.values();
    }

    public boolean sameTeam(UUID a, UUID b) {
        String ta = memberOf.get(a);
        return ta != null && ta.equals(memberOf.get(b));
    }

    public Component tagComponent(Team t) {
        if (t == null) return Component.empty();
        TextColor c = TextColor.fromHexString(t.color);
        return Component.text("[", Text.DARK).append(Component.text(t.tag, c == null ? Text.WHITE : c))
                .append(Component.text("] ", Text.DARK));
    }

    public int maxMembers() {
        return plugin.getConfig().getInt("teams.max-members", 8);
    }

    public long createCost() {
        return plugin.getConfig().getLong("teams.create-cost", 1000);
    }

    /** Succès cumulés des membres. */
    public int achievements(Team t) {
        int n = 0;
        for (UUID u : t.members) {
            PlayerStats s = plugin.stats().get(u);
            if (s != null) n += s.count();
        }
        return n;
    }

    public int seasonPoints(Team t) {
        int n = 0;
        for (UUID u : t.members) {
            Profile p = plugin.profiles().get(u);
            if (p != null) n += plugin.seasons().points(p);
        }
        return n;
    }

    public List<Team> top() {
        List<Team> list = new ArrayList<>(teams.values());
        list.sort(Comparator.comparingInt(this::achievements).reversed().thenComparing(t -> t.name));
        return list;
    }

    public String memberName(UUID u) {
        Profile p = plugin.profiles().get(u);
        if (p != null && p.name != null) return p.name;
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? "?" : n;
    }

    // ───────────────────────────── Actions ─────────────────────────────

    public void create(Player player, String name, String tag) {
        if (teamOf(player.getUniqueId()) != null) {
            plugin.msg(player, "<gray>Tu es déjà dans une équipe.");
            return;
        }
        if (!NAME.matcher(name).matches()) {
            plugin.msg(player, "<gray>Nom invalide : 3 à 16 caractères (lettres, chiffres, _ et -).");
            return;
        }
        if (!TAG.matcher(tag).matches()) {
            plugin.msg(player, "<gray>Tag invalide : 2 à 4 lettres ou chiffres.");
            return;
        }
        String id = name.toLowerCase(Locale.ROOT);
        if (teams.containsKey(id)) {
            plugin.msg(player, "<gray>Ce nom d'équipe est déjà pris.");
            return;
        }
        for (Team t : teams.values()) {
            if (t.tag.equalsIgnoreCase(tag)) {
                plugin.msg(player, "<gray>Ce tag est déjà utilisé.");
                return;
            }
        }
        long cost = createCost();
        if (cost > 0 && !plugin.economy().withdraw(player.getUniqueId(), cost)) {
            plugin.msg(player, "<gray>Créer une équipe coûte <accent>" + plugin.economy().format(cost) + "</accent>.");
            return;
        }
        Team t = new Team(id, name, tag.toUpperCase(Locale.ROOT), "#e8c872", player.getUniqueId());
        t.members.add(player.getUniqueId());
        teams.put(id, t);
        memberOf.put(player.getUniqueId(), id);
        save();
        plugin.custom().grant(player, "equipe");
        plugin.boards().markOrderDirty();
        Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <white>" + player.getName() + "</white> <gray>a fondé l'équipe ")
                .append(tagComponent(t)).append(Component.text(t.name, Text.WHITE)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
    }

    public void invite(Player player, Player target) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut inviter.");
            return;
        }
        if (teamOf(target.getUniqueId()) != null) {
            plugin.msg(player, "<white>" + target.getName() + "</white> <gray>est déjà dans une équipe.");
            return;
        }
        if (t.members.size() >= maxMembers()) {
            plugin.msg(player, "<gray>Ton équipe est complète (" + maxMembers() + " membres).");
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>()).put(t.id, System.currentTimeMillis() + 120_000);
        plugin.msg(player, "<gray>Invitation envoyée à <white>" + target.getName() + "</white>.");
        target.sendMessage(plugin.prefixed("<white>" + player.getName() + "</white> <gray>t'invite dans l'équipe ")
                .append(tagComponent(t)).append(Component.text(t.name + "  ", Text.WHITE))
                .append(plugin.text().parse("<click:run_command:'/equipe rejoindre " + t.name + "'><hover:show_text:'<gray>Rejoindre'>"
                        + "<accent>[Rejoindre]</accent></hover></click>")));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
    }

    public List<Team> invitesFor(UUID uuid) {
        Map<String, Long> m = invites.get(uuid);
        if (m == null) return List.of();
        long now = System.currentTimeMillis();
        m.values().removeIf(exp -> exp < now);
        List<Team> out = new ArrayList<>();
        for (String id : m.keySet()) {
            Team t = teams.get(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    public void join(Player player, String name) {
        Team t = byName(name);
        if (t == null) {
            plugin.msg(player, "<gray>Équipe introuvable.");
            return;
        }
        if (teamOf(player.getUniqueId()) != null) {
            plugin.msg(player, "<gray>Quitte d'abord ton équipe actuelle.");
            return;
        }
        if (!invitesFor(player.getUniqueId()).contains(t)) {
            plugin.msg(player, "<gray>Tu n'as pas d'invitation (ou elle a expiré).");
            return;
        }
        if (t.members.size() >= maxMembers()) {
            plugin.msg(player, "<gray>Cette équipe est complète.");
            return;
        }
        invites.remove(player.getUniqueId());
        t.members.add(player.getUniqueId());
        memberOf.put(player.getUniqueId(), t.id);
        save();
        plugin.custom().grant(player, "equipe");
        plugin.boards().markOrderDirty();
        broadcastTeam(t, "<white>" + player.getName() + "</white> <gray>a rejoint l'équipe !");
    }

    public void leave(Player player) {
        Team t = teamOf(player.getUniqueId());
        if (t == null) {
            plugin.msg(player, "<gray>Tu n'es dans aucune équipe.");
            return;
        }
        removeMember(t, player.getUniqueId());
        plugin.msg(player, "<gray>Tu as quitté l'équipe <white>" + t.name + "</white>.");
    }

    private void removeMember(Team t, UUID uuid) {
        t.members.remove(uuid);
        memberOf.remove(uuid);
        teamChat.remove(uuid);
        if (t.members.isEmpty()) {
            teams.remove(t.id);
        } else if (t.isOwner(uuid)) {
            t.owner = t.members.iterator().next();
            broadcastTeam(t, "<white>" + memberName(t.owner) + "</white> <gray>est le nouveau chef d'équipe.");
        }
        save();
        plugin.boards().markOrderDirty();
    }

    public void kick(Player player, String name) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut exclure.");
            return;
        }
        UUID target = null;
        for (UUID u : t.members) if (memberName(u).equalsIgnoreCase(name)) target = u;
        if (target == null || target.equals(player.getUniqueId())) {
            plugin.msg(player, "<gray>Ce joueur n'est pas dans ton équipe.");
            return;
        }
        removeMember(t, target);
        broadcastTeam(t, "<white>" + name + "</white> <gray>a été exclu de l'équipe.");
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) plugin.msg(kicked, "<gray>Tu as été exclu de l'équipe <white>" + t.name + "</white>.");
    }

    public void disband(Player player) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut dissoudre l'équipe.");
            return;
        }
        broadcastTeam(t, "<gray>L'équipe <white>" + t.name + "</white> a été dissoute.");
        for (UUID u : t.members) {
            memberOf.remove(u);
            teamChat.remove(u);
        }
        teams.remove(t.id);
        save();
        plugin.boards().markOrderDirty();
    }

    public void transfer(Player player, String name) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut faire ça.");
            return;
        }
        for (UUID u : t.members) {
            if (memberName(u).equalsIgnoreCase(name) && !u.equals(player.getUniqueId())) {
                t.owner = u;
                save();
                broadcastTeam(t, "<white>" + memberName(u) + "</white> <gray>est le nouveau chef d'équipe.");
                return;
            }
        }
        plugin.msg(player, "<gray>Ce joueur n'est pas dans ton équipe.");
    }

    public void setHome(Player player) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut définir le home d'équipe.");
            return;
        }
        t.home = StoredLocation.of(player.getLocation());
        save();
        broadcastTeam(t, "<gray>Le home d'équipe a été défini par <white>" + player.getName() + "</white>.");
    }

    public void home(Player player) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || t.home == null || t.home.toLocation() == null) {
            plugin.msg(player, "<gray>Ton équipe n'a pas de home.");
            return;
        }
        plugin.teleporter().teleport(player, t.home.toLocation(), "home d'équipe");
    }

    public void togglePvp(Player player) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut changer ça.");
            return;
        }
        t.pvp = !t.pvp;
        save();
        broadcastTeam(t, "<gray>Combat entre membres : <white>" + (t.pvp ? "activé" : "désactivé") + "</white>.");
    }

    public void setColor(Player player, String hex) {
        Team t = teamOf(player.getUniqueId());
        if (t == null || !t.isOwner(player.getUniqueId())) {
            plugin.msg(player, "<gray>Seul le chef d'équipe peut changer la couleur.");
            return;
        }
        String h = hex.startsWith("#") ? hex : "#" + hex;
        if (TextColor.fromHexString(h) == null) {
            plugin.msg(player, "<gray>Couleur invalide. Exemple : <white>#7ab8ff");
            return;
        }
        t.color = h;
        save();
        player.sendMessage(plugin.prefixed("<gray>Nouveau tag : ").append(tagComponent(t)));
    }

    public boolean toggleChat(Player player) {
        if (teamOf(player.getUniqueId()) == null) {
            plugin.msg(player, "<gray>Tu n'es dans aucune équipe.");
            return false;
        }
        boolean on = !teamChat.remove(player.getUniqueId());
        if (on) teamChat.add(player.getUniqueId());
        plugin.msg(player, "<gray>Chat d'équipe : <white>" + (on ? "activé" : "désactivé") + "</white>"
                + (on ? " <dark_gray>(commence un message par ! pour parler à tout le monde)" : ""));
        return on;
    }

    public boolean inTeamChat(UUID uuid) {
        return teamChat.contains(uuid);
    }

    public void broadcastTeam(Team t, String mini) {
        Component msg = Component.text().append(tagComponent(t)).append(plugin.text().parse(mini)).build();
        for (UUID u : t.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(msg);
        }
    }

    public void quit(UUID uuid) {
        teamChat.remove(uuid);
    }
}
