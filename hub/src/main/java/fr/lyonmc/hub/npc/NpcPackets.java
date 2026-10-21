package fr.lyonmc.hub.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Faux joueurs envoyés directement aux clients (aucune vraie entité sur le serveur) :
 * le PNJ a le vrai skin d'un joueur, n'apparaît pas dans la liste TAB et son pseudo est caché.
 */
final class NpcPackets {

    static final String TEAM = "lyonhub_npc";
    private static final EntityDataAccessor<Byte> SKIN_PARTS = skinPartsAccessor();
    private static final PlayerTeam HIDDEN_NAMES = hiddenTeam();

    private NpcPackets() {
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> skinPartsAccessor() {
        try {
            Field f = net.minecraft.world.entity.player.Player.class.getDeclaredField("DATA_PLAYER_MODE_CUSTOMISATION");
            f.setAccessible(true);
            return (EntityDataAccessor<Byte>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Index des couches du skin pour un joueur en 1.21.8
            return new EntityDataAccessor<>(17, EntityDataSerializers.BYTE);
        }
    }

    private static PlayerTeam hiddenTeam() {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), TEAM);
        team.setNameTagVisibility(Team.Visibility.NEVER);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        return team;
    }

    static void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }

    static GameProfile profile(UUID uuid, String name, String texture, String signature) {
        GameProfile gp = new GameProfile(uuid, name);
        if (texture != null && !texture.isEmpty()) gp.getProperties().put("textures", new Property("textures", texture, signature));
        return gp;
    }

    /** Équipe qui cache le pseudo des PNJ (envoyée une fois par joueur). */
    static void sendTeam(Player viewer) {
        send(viewer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(HIDDEN_NAMES, true));
    }

    static void addToTeam(Player viewer, String profileName) {
        send(viewer, ClientboundSetPlayerTeamPacket.createPlayerPacket(HIDDEN_NAMES, profileName, ClientboundSetPlayerTeamPacket.Action.ADD));
    }

    static void show(Player viewer, int entityId, GameProfile gp, Location at) {
        send(viewer, playerInfo(gp));
        send(viewer, new ClientboundAddEntityPacket(entityId, gp.getId(), at.getX(), at.getY(), at.getZ(),
                at.getPitch(), at.getYaw(), EntityType.PLAYER, 0, Vec3.ZERO, at.getYaw()));
        // Toutes les couches du skin (veste, manches, chapeau...)
        send(viewer, new ClientboundSetEntityDataPacket(entityId, List.of(SynchedEntityData.DataValue.create(SKIN_PARTS, (byte) 0x7F))));
        look(viewer, entityId, at.getYaw(), at.getPitch());
    }

    static void hide(Player viewer, int entityId, UUID profileId) {
        send(viewer, new ClientboundRemoveEntitiesPacket(entityId));
        send(viewer, new ClientboundPlayerInfoRemovePacket(List.of(profileId)));
    }

    static void look(Player viewer, int entityId, float yaw, float pitch) {
        byte y = angle(yaw);
        send(viewer, new ClientboundMoveEntityPacket.Rot(entityId, y, angle(pitch), true));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte(y);
        send(viewer, ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf));
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }

    // ───────────────────────────── Ligne de la liste des joueurs ─────────────────────────────

    /** Entrée « joueur » invisible dans TAB : nécessaire au client pour connaître le skin. */
    private static ClientboundPlayerInfoUpdatePacket playerInfo(GameProfile gp) {
        try {
            ClientboundPlayerInfoUpdatePacket.Entry entry = (ClientboundPlayerInfoUpdatePacket.Entry) entry(gp);
            return new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(entry));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Impossible de créer le paquet du PNJ", e);
        }
    }

    private static Object entry(GameProfile gp) throws ReflectiveOperationException {
        Class<?> type = ClientboundPlayerInfoUpdatePacket.Entry.class;
        RecordComponent[] rc = type.getRecordComponents();
        Class<?>[] types = new Class<?>[rc.length];
        Object[] args = new Object[rc.length];
        for (int i = 0; i < rc.length; i++) {
            Class<?> t = rc[i].getType();
            String n = rc[i].getName().toLowerCase(java.util.Locale.ROOT);
            types[i] = t;
            if (t == UUID.class) args[i] = gp.getId();
            else if (t == GameProfile.class) args[i] = gp;
            else if (t == boolean.class) args[i] = n.contains("hat"); // pas listé dans TAB, chapeau visible
            else if (t == int.class) args[i] = 0;
            else if (t == GameType.class) args[i] = GameType.SURVIVAL;
            else args[i] = null;
        }
        Constructor<?> c = type.getDeclaredConstructor(types);
        c.setAccessible(true);
        return c.newInstance(args);
    }
}
