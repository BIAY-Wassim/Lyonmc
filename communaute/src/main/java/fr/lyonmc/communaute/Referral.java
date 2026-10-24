package fr.lyonmc.communaute;

import fr.lyonmc.achievements.core.Profile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Parrainage : un nouveau choisit son parrain avec /parrain <pseudo> ; quand il a assez joué
 * (temps réel, hors AFK et hub), les deux sont récompensés. À 3 filleuls : titre Ambassadeur.
 */
public final class Referral {

    private final Communaute plugin;

    Referral(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("parrainage.enabled", true);
    }

    private double requiredHours() {
        return plugin.getConfig().getDouble("parrainage.heures-requises", 2);
    }

    void command(Player p, String[] args) {
        if (!enabled()) {
            plugin.msg(p, "<gray>Le parrainage est désactivé.");
            return;
        }
        PlayerData d = plugin.store().get(p);
        if (args.length == 0) {
            info(p, d);
            return;
        }
        if (args[0].equalsIgnoreCase("top")) {
            top(p);
            return;
        }
        if (d.parrain != null) {
            plugin.msg(p, "<gray>Tu as déjà un parrain.");
            return;
        }
        double limit = plugin.getConfig().getDouble("parrainage.delai-heures", 5);
        if (d.activeSeconds > limit * 3600) {
            plugin.msg(p, "<gray>Le parrainage se choisit pendant les <white>" + fmt(limit) + " premières heures</white> de jeu.");
            return;
        }
        Profile target = plugin.lyon().profiles().byName(args[0]);
        if (target == null) {
            plugin.msg(p, "<gray>Aucun joueur nommé <white>" + args[0].replace("<", "") + "</white> n'est déjà venu sur le serveur.");
            return;
        }
        if (target.uuid().equals(p.getUniqueId())) {
            plugin.msg(p, "<gray>Tu ne peux pas être ton propre parrain.");
            return;
        }
        PlayerData t = plugin.store().getOrCreate(target.uuid(), target.name);
        if (p.getUniqueId().equals(t.parrain)) {
            plugin.msg(p, "<gray>C'est toi qui as parrainé <white>" + target.name + "</white> : impossible dans l'autre sens.");
            return;
        }
        if (!plugin.sameIpAllowed() && d.ipHash != null && d.ipHash.equals(t.ipHash)) {
            plugin.msg(p, "<gray>Impossible : vous jouez depuis la même connexion internet.");
            return;
        }
        d.parrain = target.uuid();
        plugin.msg(p, "<white>" + target.name + "</white> <gray>est maintenant ton parrain. Quand tu auras joué <white>" + fmt(requiredHours())
                + " h</white> (hors AFK), vous serez récompensés tous les deux : ");
        p.sendMessage(Component.text("   ").append(plugin.describe("parrainage.recompense-filleul")));
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            plugin.msg(online, "<white>" + p.getName() + "</white> <gray>t'a choisi comme parrain ! Récompense quand il aura joué <white>"
                    + fmt(requiredHours()) + " h</white> : ");
            online.sendMessage(Component.text("   ").append(plugin.describe("parrainage.recompense-parrain")));
        }
    }

    private void info(Player p, PlayerData d) {
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.parse("  <#7cd4ff>✦</#7cd4ff> <white><bold>Parrainage</bold></white>"));
        p.sendMessage(plugin.parse("  <gray>Invite tes amis : à leur arrivée, ils tapent <white>/parrain " + p.getName() + "</white>."));
        p.sendMessage(plugin.parse("  <gray>Quand ils ont joué <white>" + fmt(requiredHours()) + " h</white>, tu reçois ").append(plugin.describe("parrainage.recompense-parrain")));
        p.sendMessage(plugin.parse("  <gray>et eux ").append(plugin.describe("parrainage.recompense-filleul")));
        int need = plugin.getConfig().getInt("parrainage.ambassadeur.filleuls", 3);
        p.sendMessage(plugin.parse("  <gray>Tes filleuls : <white>" + d.filleuls + "</white>" + (need > 0 && !d.ambassadeur
                ? " <dark_gray>(" + need + " pour le titre Ambassadeur)" : "")));
        if (d.parrain != null) {
            Profile pr = plugin.lyon().profiles().get(d.parrain);
            String name = pr == null ? "?" : pr.name;
            p.sendMessage(plugin.parse("  <gray>Ton parrain : <white>" + name + "</white>" + (d.parrainDone ? " <green>✔ récompensés</green>"
                    : " <dark_gray>(" + fmt(d.activeSeconds / 3600.0) + " h / " + fmt(requiredHours()) + " h)")));
        } else {
            double limit = plugin.getConfig().getDouble("parrainage.delai-heures", 5);
            if (d.activeSeconds <= limit * 3600) p.sendMessage(plugin.parse("  <gray>Un ami t'a fait venir ? <white>/parrain <son pseudo>"));
        }
        p.sendMessage(plugin.parse("  <dark_gray>/parrain top : les meilleurs parrains"));
        p.sendMessage(Component.empty());
    }

    private void top(Player p) {
        List<PlayerData> list = top(10);
        p.sendMessage(plugin.parse("  <#7cd4ff>✦</#7cd4ff> <white><bold>Meilleurs parrains</bold></white>"));
        if (list.isEmpty()) p.sendMessage(plugin.parse("  <dark_gray>Personne pour l'instant : sois le premier !"));
        for (int i = 0; i < list.size(); i++) {
            p.sendMessage(plugin.parse("  <gray>" + (i + 1) + ". <white>" + list.get(i).name + "</white> <dark_gray>" + list.get(i).filleuls + " filleul(s)"));
        }
    }

    public List<PlayerData> top(int n) {
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData d : plugin.store().all()) if (d.filleuls > 0) list.add(d);
        list.sort(Comparator.comparingInt((PlayerData d) -> d.filleuls).reversed());
        return list.size() > n ? list.subList(0, n) : list;
    }

    /** Chaque minute : les filleuls qui ont assez joué. */
    void tick() {
        if (!enabled()) return;
        long need = (long) (requiredHours() * 3600);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.store().get(p);
            if (d.parrain == null || d.parrainDone || d.activeSeconds < need) continue;
            d.parrainDone = true;
            plugin.give(p, "parrainage.recompense-filleul");
            p.sendMessage(plugin.lyon().prefixed("<gray>Parrainage validé ! ").append(plugin.describe("parrainage.recompense-filleul")));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
            Profile pr = plugin.lyon().profiles().get(d.parrain);
            String parrainName = pr == null ? "?" : pr.name;
            PlayerData parrain = plugin.store().getOrCreate(d.parrain, parrainName);
            parrain.filleuls++;
            plugin.giveOrQueue(d.parrain, parrainName, "parrainage.recompense-parrain");
            Bukkit.broadcast(plugin.parse("<#7cd4ff>✦</#7cd4ff> <white>" + parrainName + "</white> <gray>a fait venir <white>" + p.getName()
                    + "</white> sur LyonMC. Merci ! <dark_gray>(/parrain)"));
            int threshold = plugin.getConfig().getInt("parrainage.ambassadeur.filleuls", 3);
            if (threshold > 0 && !parrain.ambassadeur && parrain.filleuls >= threshold) {
                parrain.ambassadeur = true;
                plugin.giveOrQueue(d.parrain, parrainName, "parrainage.ambassadeur.recompense");
                Bukkit.broadcast(plugin.parse("<#7cd4ff>✦</#7cd4ff> <white>" + parrainName + "</white> <gray>devient <#7cd4ff>Ambassadeur</#7cd4ff> de LyonMC !"));
            }
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.FRANCE, "%.1f", d);
    }
}
