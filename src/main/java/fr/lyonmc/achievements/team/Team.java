package fr.lyonmc.achievements.team;

import fr.lyonmc.achievements.core.StoredLocation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Une équipe de joueurs. */
public final class Team {

    public final String id;
    public String name;
    public String tag;
    public String color;
    public UUID owner;
    public final Set<UUID> members = new LinkedHashSet<>();
    public long created;
    public StoredLocation home;
    public boolean pvp;

    public Team(String id, String name, String tag, String color, UUID owner) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.color = color;
        this.owner = owner;
        this.created = System.currentTimeMillis();
    }

    public boolean isOwner(UUID uuid) {
        return owner.equals(uuid);
    }
}
