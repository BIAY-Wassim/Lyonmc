package fr.lyonmc.achievements.api;

import java.util.UUID;

/**
 * Fourni par un autre plugin (ex. : COMMUNAUTE) via le ServicesManager de Bukkit :
 * LyonAchievements affiche alors « AFK » dans la liste des joueurs.
 */
public interface AfkProvider {
    boolean isAfk(UUID uuid);
}
