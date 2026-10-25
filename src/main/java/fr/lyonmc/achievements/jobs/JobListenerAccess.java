package fr.lyonmc.achievements.jobs;

import org.bukkit.Material;

/** Accès public à quelques règles des métiers (pour l'export du site). */
public final class JobListenerAccess {

    private JobListenerAccess() {
    }

    /** Culture qui ne compte que mûre. */
    public static boolean isCrop(Material m) {
        return JobListener.isCrop(m);
    }
}
