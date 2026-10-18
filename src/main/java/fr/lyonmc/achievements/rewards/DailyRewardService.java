package fr.lyonmc.achievements.rewards;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Récompense de connexion quotidienne, en cycle de 7 jours avec série. */
public final class DailyRewardService {

    private final LyonAchievements plugin;
    private final List<Bundle> cycle = new ArrayList<>();

    public DailyRewardService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public void load() {
        cycle.clear();
        for (Map<?, ?> m : plugin.configs().rewards().getMapList("daily-login")) cycle.add(Bundle.of(m));
        if (cycle.isEmpty()) cycle.add(new Bundle(50, List.of(), null, null, 0, 0, 0, List.of(), List.of(), 0));
    }

    public List<Bundle> cycle() {
        return cycle;
    }

    private LocalDate today() {
        return LocalDate.now(plugin.text().zone());
    }

    public boolean canClaim(Profile p) {
        return !today().toString().equals(p.lastLoginClaim);
    }

    /** Série si le joueur réclame aujourd'hui (la série continue si la dernière fois était hier). */
    public int streakIfClaimed(Profile p) {
        if (!canClaim(p)) return p.loginStreak;
        return today().minusDays(1).toString().equals(p.lastLoginClaim) ? p.loginStreak + 1 : 1;
    }

    /** Série actuelle, remise à 0 si un jour a été raté. */
    public int currentStreak(Profile p) {
        if (p.lastLoginClaim == null) return 0;
        String last = p.lastLoginClaim;
        if (last.equals(today().toString()) || last.equals(today().minusDays(1).toString())) return p.loginStreak;
        return 0;
    }

    /** Index (0-6) du jour du cycle pour une série donnée. */
    public int dayIndex(int streak) {
        return Math.max(0, streak - 1) % cycle.size();
    }

    public boolean claim(Player player) {
        Profile p = plugin.profiles().get(player);
        if (!canClaim(p)) return false;
        int streak = streakIfClaimed(p);
        Bundle b = cycle.get(dayIndex(streak));
        p.loginStreak = streak;
        p.bestLoginStreak = Math.max(p.bestLoginStreak, streak);
        p.lastLoginClaim = today().toString();
        p.dirty();
        plugin.rewards().grant(player, b);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        plugin.msg(player, "<gray>Récompense du jour <white>" + (dayIndex(streak) + 1) + "/" + cycle.size()
                + "</white> récupérée <dark_gray>· série de " + streak + " jour" + (streak > 1 ? "s" : ""));
        if (streak >= 7) plugin.custom().grant(player, "fidele");
        plugin.rewards().check(player);
        return true;
    }

    public void notifyJoin(Player player) {
        Profile p = plugin.profiles().get(player);
        if (!canClaim(p)) return;
        player.sendMessage(plugin.prefixed("<gray>Ta récompense du jour t'attend : <click:run_command:'/recompense'><hover:show_text:'<gray>Récupérer'>"
                + "<accent><u>/recompense</u></accent></hover></click> <dark_gray>(série " + currentStreak(p) + ")"));
    }
}
