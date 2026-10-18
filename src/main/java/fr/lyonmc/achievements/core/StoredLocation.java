package fr.lyonmc.achievements.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/** Position sauvegardable (le monde peut ne pas être chargé). */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLocation of(Location l) {
        return new StoredLocation(l.getWorld() == null ? "world" : l.getWorld().getName(),
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** null si le monde n'existe plus. */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public String serialize() {
        return world + ";" + fmt(x) + ";" + fmt(y) + ";" + fmt(z) + ";" + fmt(yaw) + ";" + fmt(pitch);
    }

    public static StoredLocation parse(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length < 4) return null;
        try {
            return new StoredLocation(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    public String pretty() {
        return world + " " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }
}
