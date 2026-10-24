package fr.lyonmc.communaute;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ce que COMMUNAUTE retient d'un joueur (plugins/COMMUNAUTE/joueurs.yml). */
public final class PlayerData {

    public final UUID uuid;
    public String name;
    public long firstSeen;
    public long lastSeen;
    /** Temps de jeu réel (hors AFK, hors hub), en secondes. */
    public long activeSeconds;
    public String ipHash;

    // Premiers pas : actif, fini ou ignore
    public String tutorial = "actif";
    public final Set<String> steps = new LinkedHashSet<>();
    public boolean saidHello;
    public boolean visitedMine;
    public boolean hideBar;
    public boolean kitGiven;

    // Parrainage
    public UUID parrain;
    public boolean parrainDone;
    public int filleuls;
    public boolean ambassadeur;

    // Votes
    public int votes;
    public String voteMonth;
    public int monthVotes;
    public long lastVote;
    public int streak;
    public int pendingVotes;

    // Fidélité
    public long playAccumulator;

    // Objectif de la semaine
    public String goalWeek;
    public int contribution;

    /** Récompenses à donner à la prochaine connexion (chemins de lots dans config.yml). */
    public final List<String> pending = new ArrayList<>();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    static PlayerData load(UUID uuid, ConfigurationSection c) {
        PlayerData d = new PlayerData(uuid, c.getString("nom"));
        d.firstSeen = c.getLong("premiere-venue");
        d.lastSeen = c.getLong("derniere-venue");
        d.activeSeconds = c.getLong("temps-actif");
        d.ipHash = c.getString("ip");
        d.tutorial = c.getString("premiers-pas.etat", "actif");
        d.steps.addAll(c.getStringList("premiers-pas.etapes"));
        d.saidHello = c.getBoolean("premiers-pas.chat");
        d.visitedMine = c.getBoolean("premiers-pas.mine");
        d.hideBar = c.getBoolean("premiers-pas.cacher");
        d.kitGiven = c.getBoolean("premiers-pas.kit");
        String p = c.getString("parrainage.parrain");
        try {
            d.parrain = p == null ? null : UUID.fromString(p);
        } catch (IllegalArgumentException ignored) {
        }
        d.parrainDone = c.getBoolean("parrainage.recompense");
        d.filleuls = c.getInt("parrainage.filleuls");
        d.ambassadeur = c.getBoolean("parrainage.ambassadeur");
        d.votes = c.getInt("votes.total");
        d.voteMonth = c.getString("votes.mois");
        d.monthVotes = c.getInt("votes.ce-mois");
        d.lastVote = c.getLong("votes.dernier");
        d.streak = c.getInt("votes.serie");
        d.pendingVotes = c.getInt("votes.en-attente");
        d.playAccumulator = c.getLong("temps-de-jeu");
        d.goalWeek = c.getString("objectif.semaine");
        d.contribution = c.getInt("objectif.participation");
        d.pending.addAll(c.getStringList("en-attente"));
        return d;
    }

    void save(ConfigurationSection c) {
        c.set("nom", name);
        c.set("premiere-venue", firstSeen);
        c.set("derniere-venue", lastSeen);
        c.set("temps-actif", activeSeconds);
        c.set("ip", ipHash);
        c.set("premiers-pas.etat", tutorial);
        c.set("premiers-pas.etapes", new ArrayList<>(steps));
        c.set("premiers-pas.chat", saidHello);
        c.set("premiers-pas.mine", visitedMine);
        c.set("premiers-pas.cacher", hideBar);
        c.set("premiers-pas.kit", kitGiven);
        c.set("parrainage.parrain", parrain == null ? null : parrain.toString());
        c.set("parrainage.recompense", parrainDone);
        c.set("parrainage.filleuls", filleuls);
        c.set("parrainage.ambassadeur", ambassadeur);
        c.set("votes.total", votes);
        c.set("votes.mois", voteMonth);
        c.set("votes.ce-mois", monthVotes);
        c.set("votes.dernier", lastVote);
        c.set("votes.serie", streak);
        c.set("votes.en-attente", pendingVotes);
        c.set("temps-de-jeu", playAccumulator);
        c.set("objectif.semaine", goalWeek);
        c.set("objectif.participation", contribution);
        c.set("en-attente", new ArrayList<>(pending));
    }

    /** Votes de ce mois (0 si le dernier vote date d'un autre mois). */
    public int votesIn(String month) {
        return month.equals(voteMonth) ? monthVotes : 0;
    }

    /** Participation à l'objectif de cette semaine. */
    public int contributionIn(String week) {
        return week.equals(goalWeek) ? contribution : 0;
    }
}
