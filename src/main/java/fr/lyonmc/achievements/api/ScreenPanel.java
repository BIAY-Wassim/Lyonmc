package fr.lyonmc.achievements.api;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Panneau supplémentaire du grand écran du hub (/lmc holo creer ecran),
 * fourni par un autre plugin via le ServicesManager de Bukkit.
 */
public interface ScreenPanel {
    /** Titre du panneau (une ligne). */
    Component title();

    /** Lignes du panneau (10 au maximum conseillé). */
    List<Component> lines();
}
