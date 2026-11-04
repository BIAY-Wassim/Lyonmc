package fr.lyonmc.achievements.boss;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss mondial : apparaît aux horaires prévus dans l'arène, capacités spéciales,
 * barre de vie, récompenses selon les dégâts infligés.
 */
public final class BossService implements Listener {

    private final LyonAchievements plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;

    private LivingEntity boss;
    private BossBar bar;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final List<Entity> minions = new ArrayList<>();
    private long startedAt;
    private int tickCount;
    private final Set<String> firedKeys = new HashSet<>();

    public BossService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.minionKey = new NamespacedKey(plugin, "boss_minion");
    }

    private ConfigurationSection cfg() {
        return plugin.configs().events().getConfigurationSection("boss");
    }

    public boolean active() {
        return boss != null && boss.isValid() && !boss.isDead();
    }

    public Location arena() {
        StoredLocation s = StoredLocation.parse(plugin.configs().state().getString("boss.arena"));
        return s == null ? null : s.toLocation();
    }

    public void setArena(Location l) {
        plugin.configs().state().set("boss.arena", StoredLocation.of(l).serialize());
        plugin.configs().saveState();
    }

    /** Supprime les restes d'un boss précédent (redémarrage). */
    public void cleanup() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                        || e.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) e.remove();
            }
        }
    }

    // ───────────────────────────── Planning ─────────────────────────────

    private record Slot(DayOfWeek day, LocalTime time) {
    }

    private List<Slot> schedule() {
        List<Slot> out = new ArrayList<>();
        if (cfg() == null) return out;
        for (String s : cfg().getStringList("schedule")) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 2) continue;
            try {
                out.add(new Slot(DayOfWeek.valueOf(p[0].toUpperCase(Locale.ROOT)), LocalTime.parse(p[1])));
            } catch (Exception e) {
                plugin.getLogger().warning("Horaire de boss invalide : " + s);
            }
        }
        return out;
    }

    /** Prochaine apparition prévue. */
    public ZonedDateTime next() {
        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone());
        ZonedDateTime best = null;
        for (Slot s : schedule()) {
            ZonedDateTime c = now.with(TemporalAdjusters.nextOrSame(s.day())).with(s.time()).withSecond(0).withNano(0);
            if (c.isBefore(now)) c = c.plusWeeks(1);
            if (best == null || c.isBefore(best)) best = c;
        }
        return best;
    }

    public String nextText() {
        if (arena() == null) return "pas encore programmée (arène à définir)";
        ZonedDateTime n = next();
        if (n == null) return "aucune apparition prévue";
        long minutes = ChronoUnit.MINUTES.between(ZonedDateTime.now(plugin.text().zone()), n);
        String day = n.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE);
        String when = day + " à " + n.toLocalTime().toString();
        if (minutes < 60) return "dans " + Math.max(1, minutes) + " min";
        if (minutes < 24 * 60) return "dans " + (minutes / 60) + " h " + (minutes % 60) + " min (" + when + ")";
        return when;
    }

    /** Appelé toutes les 20 secondes. */
    public void tickSchedule() {
        if (cfg() == null || !cfg().getBoolean("enabled", true) || !plugin.module("boss") || arena() == null) return;
        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone()).withSecond(0).withNano(0);
        for (Slot s : schedule()) {
            ZonedDateTime at = now.with(TemporalAdjusters.nextOrSame(s.day())).with(s.time());
            if (at.isBefore(now)) at = at.plusWeeks(1);
            long minutes = ChronoUnit.MINUTES.between(now, at);
            String base = at.toLocalDate() + "T" + s.time();
            for (int w : cfg().getIntegerList("warnings-minutes")) {
                if (minutes == w && firedKeys.add(base + "-w" + w)) warn(w);
            }
            if (minutes == 0 && firedKeys.add(base + "-start") && !active()) start(null);
        }
        if (firedKeys.size() > 200) firedKeys.clear();
    }

    private void warn(int minutes) {
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(plugin.text().parse(cfg().getString("name", "Le Colosse")))
                .append(plugin.text().parse(" <gray>apparaît dans <white>" + minutes + " min</white>. "
                        + "<click:run_command:'/boss'><hover:show_text:'<gray>Rejoindre l'arène'><accent><u>/boss</u></accent></hover></click>")));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 0.8f);
        if (minutes == cfg().getIntegerList("warnings-minutes").stream().mapToInt(i -> i).max().orElse(-1)) {
            plugin.discord().send("Boss mondial", "Le boss mondial apparaît dans " + minutes + " minutes sur LyonMC !", 0xFF6B6B, null);
        }
    }

    // ───────────────────────────── Combat ─────────────────────────────

    /** Réglage du boss (les valeurs absentes de evenements.yml viennent du fichier du plugin). */
    private double num(String key) {
        return plugin.configs().events().getDouble("boss." + key);
    }

    /** Vie du boss : elle est comptée à part (la vie de Minecraft est limitée à 1024). */
    private double hp, maxHp;
    /** Phase actuelle (0 = début) et bouclier en cours (gardiens à tuer). */
    private int phase;
    private boolean shield;
    private final List<Entity> guardians = new ArrayList<>();
    /** Dernier coup porté par le boss à un joueur (pour savoir s'il est coincé). */
    private long lastBossHit;
    private int players;

    public boolean start(Location override) {
        if (active()) return false;
        Location at = override != null ? override : arena();
        if (at == null || at.getWorld() == null) {
            plugin.getLogger().warning("Boss : aucune arène définie (/boss arene).");
            return false;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(cfg().getString("entity", "WITHER_SKELETON").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.WITHER_SKELETON;
        }
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) cls = WitherSkeleton.class;

        int nearby = 0;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 128 * 128) nearby++;
        players = Math.max(1, Math.max(nearby, Math.min(Bukkit.getOnlinePlayers().size(), 10)));
        maxHp = Math.max(100, num("vie-base") + num("vie-par-joueur") * players);
        hp = maxHp;
        phase = 0;
        shield = false;

        Component name = plugin.text().parse(cfg().getString("name", "Le Colosse"));
        Entity e = at.getWorld().spawn(at, cls, ent -> {
            LivingEntity le = (LivingEntity) ent;
            le.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            le.customName(name);
            le.setCustomNameVisible(true);
            le.setRemoveWhenFarAway(false);
            le.setPersistent(false);
            set(le, Attribute.MAX_HEALTH, 1024);
            set(le, Attribute.SCALE, cfg().getDouble("scale", 2.6));
            set(le, Attribute.ATTACK_DAMAGE, num("damage"));
            set(le, Attribute.ARMOR, num("armor"));
            set(le, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            set(le, Attribute.FOLLOW_RANGE, 48);
            set(le, Attribute.MOVEMENT_SPEED, 0.3);
            le.setHealth(1024);
            if (le.getEquipment() != null) {
                le.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.NETHERITE_SWORD));
                le.getEquipment().setItemInMainHandDropChance(0f);
                le.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.NETHERITE_HELMET));
                le.getEquipment().setHelmetDropChance(0f);
            }
            le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        });
        boss = (LivingEntity) e;
        damage.clear();
        startedAt = System.currentTimeMillis();
        lastBossHit = startedAt;
        tickCount = 0;
        bar = BossBar.bossBar(name, 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

        at.getWorld().strikeLightningEffect(at);
        at.getWorld().playSound(at, Sound.ENTITY_WITHER_SPAWN, 2f, 0.8f);
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(name)
                .append(plugin.text().parse(" <gray>est apparu ! Rejoins le combat : "
                        + "<click:run_command:'/boss'><hover:show_text:'<gray>Rejoindre l'arène'><accent><u>/boss</u></accent></hover></click>")));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(name, Component.text("est apparu · /boss", Text.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        }
        plugin.discord().send("Boss mondial", "Le boss mondial vient d'apparaître sur LyonMC !", 0xFF6B6B, null);
        return true;
    }

    private static void set(LivingEntity e, Attribute a, double value) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(value);
    }

    /** Appelé chaque seconde. */
    public void tick() {
        if (boss == null) return;
        if (!active()) {
            end(false, boss.isDead() ? null : "<gray>Le boss a disparu.");
            return;
        }
        tickCount++;
        long duration = cfg().getLong("duration-minutes", 20) * 60_000L;
        if (System.currentTimeMillis() - startedAt > duration) {
            end(false, "<gray>Le boss s'est enfui... Il reviendra plus fort.");
            return;
        }
        guardians.removeIf(g -> !g.isValid() || g.isDead());
        minions.removeIf(m -> !m.isValid() || m.isDead());
        if (shield && guardians.isEmpty()) breakShield();
        updateBar();
        Set<UUID> now = new HashSet<>();
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(boss.getLocation()) < 64 * 64) {
                now.add(p.getUniqueId());
                if (viewers.add(p.getUniqueId())) p.showBossBar(bar);
            }
        }
        for (UUID u : new HashSet<>(viewers)) {
            if (!now.contains(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
                viewers.remove(u);
            }
        }
        Player nearest = nearest(32);
        if (boss instanceof Mob mob && (mob.getTarget() == null || !(mob.getTarget() instanceof Player) || mob.getTarget().isDead())) {
            if (nearest != null) mob.setTarget(nearest);
        }
        // Personne autour : il reprend des forces
        if (nearest == null && hp < maxHp && tickCount % 5 == 0) hp = Math.min(maxHp, hp + maxHp * 0.01);
        antiCheese();
        int every = switch (phase) {
            case 0 -> 7;
            case 1 -> 5;
            default -> 4;
        };
        if (tickCount % every == 0) ability();
    }

    private void updateBar() {
        float progress = (float) Math.max(0, Math.min(1, hp / maxHp));
        bar.progress(progress);
        Component name = plugin.text().parse(cfg().getString("name", "Le Colosse"));
        if (shield) {
            bar.color(BossBar.Color.PURPLE);
            bar.name(name.append(plugin.text().parse(" <dark_gray>·</dark_gray> <light_purple>bouclier : " + guardians.size() + " gardien(s) à vaincre")));
        } else {
            bar.color(phase >= 2 ? BossBar.Color.RED : phase == 1 ? BossBar.Color.YELLOW : BossBar.Color.RED);
            bar.name(name.append(plugin.text().parse(" <dark_gray>·</dark_gray> <gray>phase " + (phase + 1) + "/" + (phases().size() + 1)
                    + " <dark_gray>·</dark_gray> <white>" + String.format(Locale.FRANCE, "%,.0f", Math.ceil(hp)) + "</white> <red>❤")));
        }
        // La vie affichée au-dessus de lui suit sa vraie vie
        boss.setHealth(Math.max(1, Math.min(1024, 1024 * hp / maxHp)));
    }

    private List<Integer> phases() {
        List<Integer> out = plugin.configs().events().getIntegerList("boss.phases");
        if (out.isEmpty() && !plugin.configs().events().isSet("boss.phases")) out = List.of(66, 33);
        return out;
    }

    /** Il bloque ou on le tape de loin : il revient au contact. */
    private void antiCheese() {
        if (!(boss instanceof Mob mob) || !(mob.getTarget() instanceof Player t) || shield) return;
        double dy = t.getLocation().getY() - boss.getLocation().getY();
        double d2 = t.getLocation().distanceSquared(boss.getLocation());
        boolean stuck = System.currentTimeMillis() - lastBossHit > 15_000L;
        if ((dy > 4 && d2 < 30 * 30) || stuck || d2 > 40 * 40) {
            Location to = t.getLocation().clone().add(t.getLocation().getDirection().setY(0).normalize().multiply(-2));
            if (!to.getBlock().isPassable()) to = t.getLocation();
            World w = boss.getWorld();
            w.spawnParticle(Particle.PORTAL, boss.getLocation().add(0, 2, 0), 60, 1, 2, 1, 0.3);
            boss.teleport(to);
            w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
            lastBossHit = System.currentTimeMillis();
            say("<gray>surgit derrière <white>" + t.getName() + "</white> !");
        }
    }

    private double maxHealth() {
        return maxHp;
    }

    private Player nearest(double radius) {
        Player best = null;
        double bestD = radius * radius;
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(boss.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private List<Player> around(double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distanceSquared(boss.getLocation()) < radius * radius) out.add(p);
        }
        return out;
    }

    // ───────────────────────────── Phases et bouclier ─────────────────────────────

    private void checkPhase() {
        List<Integer> ph = phases();
        if (phase >= ph.size() || shield) return;
        if (hp / maxHp * 100 <= ph.get(phase)) {
            phase++;
            raiseShield();
        }
    }

    private void raiseShield() {
        shield = true;
        Location loc = boss.getLocation();
        World w = loc.getWorld();
        int n = (int) Math.min(num("gardiens-max"), Math.max(2, num("gardiens-par-joueur") * Math.max(players, around(48).size())));
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n;
            Location l = loc.clone().add(Math.cos(ang) * 7, 0.5, Math.sin(ang) * 7);
            if (!l.getBlock().isPassable()) l = loc.clone().add(0, 0.5, 0);
            Entity g = w.spawn(l, WitherSkeleton.class, this::tagGuardian);
            guardians.add(g);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.02);
        }
        boss.setGlowing(true);
        boss.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 600, 3, false, false));
        w.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);
        w.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 2f, 0.5f);
        Component name = plugin.text().parse(cfg().getString("name", "Le Colosse"));
        for (Player p : around(64)) {
            p.showTitle(Title.title(plugin.text().parse("<light_purple>Bouclier !"),
                    plugin.text().parse("<gray>Tuez les <white>" + n + "</white> gardiens pour le briser"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
        }
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(name)
                .append(plugin.text().parse(" <gray>passe en <white>phase " + (phase + 1) + "</white> et s'entoure de ses gardiens !")));
    }

    private void breakShield() {
        shield = false;
        boss.setGlowing(false);
        boss.removePotionEffect(PotionEffectType.SLOWNESS);
        if (phase >= 2) {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, boss.getLocation(), 1);
        for (Player p : around(64)) {
            p.showTitle(Title.title(plugin.text().parse("<white>Bouclier brisé !"),
                    plugin.text().parse(phase >= 2 ? "<red>Il est fou de rage..." : "<gray>Frappez-le !"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        }
    }

    private void tagGuardian(Entity ent) {
        ent.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 2);
        ent.setPersistent(false);
        if (!(ent instanceof LivingEntity le)) return;
        le.customName(plugin.text().parse("<light_purple>Gardien du Colosse"));
        le.setCustomNameVisible(true);
        le.setGlowing(true);
        set(le, Attribute.MAX_HEALTH, num("gardien-vie"));
        le.setHealth(num("gardien-vie"));
        set(le, Attribute.ARMOR, 14);
        set(le, Attribute.ATTACK_DAMAGE, 9);
        set(le, Attribute.KNOCKBACK_RESISTANCE, 0.6);
        set(le, Attribute.SCALE, 1.25);
        if (le.getEquipment() != null) {
            org.bukkit.inventory.EntityEquipment eq = le.getEquipment();
            eq.setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.NETHERITE_AXE));
            eq.setChestplate(new org.bukkit.inventory.ItemStack(Material.NETHERITE_CHESTPLATE));
            eq.setHelmet(new org.bukkit.inventory.ItemStack(Material.WITHER_SKELETON_SKULL));
            eq.setItemInMainHandDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setHelmetDropChance(0f);
        }
        le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    private void tagMinion(Entity ent) {
        ent.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
        ent.setPersistent(false);
        if (!(ent instanceof LivingEntity le)) return;
        double hpM = num("sbire-vie");
        set(le, Attribute.MAX_HEALTH, hpM);
        le.setHealth(hpM);
        if (le.getEquipment() != null && !(le instanceof Vex) && !(le instanceof Blaze)) {
            org.bukkit.inventory.EntityEquipment eq = le.getEquipment();
            eq.setHelmet(new org.bukkit.inventory.ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new org.bukkit.inventory.ItemStack(phase >= 1 ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            if (le instanceof Zombie || le instanceof WitherSkeleton) {
                eq.setItemInMainHand(new org.bukkit.inventory.ItemStack(phase >= 1 ? Material.DIAMOND_SWORD : Material.IRON_SWORD));
                eq.setItemInMainHandDropChance(0f);
            }
        }
        if (le instanceof Zombie z) z.setShouldBurnInDay(false);
        if (le instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
        le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    // ───────────────────────────── Capacités ─────────────────────────────

    private void ability() {
        List<Integer> pool = new ArrayList<>(List.of(0, 1, 2, 3));      // onde de choc, sbires, foudre, bond
        if (phase >= 1) pool.addAll(List.of(4, 5));                     // crocs, pluie de feu
        if (phase >= 2) pool.addAll(List.of(6, 7));                     // ténèbres, attraction
        if (shield) pool.removeAll(List.of(1, 3, 7));                   // pendant le bouclier, il reste au centre
        int choice = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Location loc = boss.getLocation();
        World w = loc.getWorld();
        double power = 1 + phase * 0.35;
        switch (choice) {
            case 0 -> { // Onde de choc
                w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION, loc, 8, 2.5, 0.5, 2.5, 0);
                for (Player p : around(10)) {
                    Vector push = p.getLocation().toVector().subtract(loc.toVector()).setY(0);
                    if (push.lengthSquared() < 0.01) push = new Vector(0.1, 0, 0);
                    p.setVelocity(push.normalize().multiply(1.7).setY(0.75));
                    p.damage(7 * power, boss);
                }
                say("<gray>déclenche une onde de choc !");
            }
            case 1 -> summon(w, loc);
            case 2 -> { // Foudre
                List<Player> targets = around(24);
                Collections.shuffle(targets);
                for (int i = 0; i < Math.min(2 + phase, targets.size()); i++) {
                    Player t = targets.get(i);
                    w.strikeLightningEffect(t.getLocation());
                    t.damage(6 * power, boss);
                    t.setFireTicks(80);
                }
                say("<gray>appelle la foudre !");
            }
            case 3 -> { // Bond vers le joueur le plus éloigné
                Player far = null;
                double best = 0;
                for (Player p : around(28)) {
                    double d = p.getLocation().distanceSquared(loc);
                    if (d > best) {
                        best = d;
                        far = p;
                    }
                }
                if (far != null) {
                    Vector dir = far.getLocation().toVector().subtract(loc.toVector());
                    boss.setVelocity(dir.multiply(0.18).setY(0.9));
                    w.playSound(loc, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
                    say("<gray>bondit sur <white>" + far.getName() + "</white> !");
                }
            }
            case 4 -> { // Crocs : une ligne de crocs vers chaque joueur proche
                for (Player p : around(16)) {
                    Vector dir = p.getLocation().toVector().subtract(loc.toVector()).setY(0);
                    if (dir.lengthSquared() < 0.01) continue;
                    dir.normalize();
                    for (int i = 1; i <= 14; i++) {
                        Location f = loc.clone().add(dir.clone().multiply(i * 1.1));
                        f.setY(ground(f));
                        int delay = i;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> w.spawn(f, EvokerFangs.class, fang -> fang.setOwner(boss)), delay);
                    }
                }
                w.playSound(loc, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 0.7f);
                say("<gray>fait jaillir des crocs du sol !");
            }
            case 5 -> { // Pluie de feu au-dessus des joueurs
                for (Player p : around(24)) {
                    for (int i = 0; i < 3 + phase; i++) {
                        Location from = p.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 14, ThreadLocalRandom.current().nextDouble(-3, 3));
                        int delay = i * 6;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (boss == null) return;
                            w.spawn(from, SmallFireball.class, fb -> {
                                fb.setShooter(boss);
                                fb.setDirection(new Vector(0, -1, 0));
                                fb.setIsIncendiary(false);
                            });
                        }, delay);
                    }
                }
                w.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
                say("<gray>fait pleuvoir le feu !");
            }
            case 6 -> { // Ténèbres
                for (Player p : around(30)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 6, 0, false, false));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 4, 1, false, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 5, 0, false, true));
                }
                w.playSound(loc, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.6f);
                say("<gray>plonge l'arène dans les ténèbres !");
            }
            default -> { // Attraction puis explosion
                for (Player p : around(18)) {
                    Vector pull = loc.toVector().subtract(p.getLocation().toVector());
                    if (pull.lengthSquared() < 0.01) continue;
                    p.setVelocity(pull.normalize().multiply(1.4).setY(0.35));
                }
                w.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.6f);
                say("<red>attire tout le monde à lui... ÉLOIGNEZ-VOUS !");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (boss == null || !active()) return;
                    Location l = boss.getLocation();
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 2);
                    w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
                    for (Player p : around(6)) p.damage(14, boss);
                }, 50L);
            }
        }
    }

    private static double ground(Location l) {
        Location c = l.clone();
        for (int i = 0; i < 6 && c.getBlock().isPassable(); i++) c.add(0, -1, 0);
        for (int i = 0; i < 6 && !c.getBlock().isPassable(); i++) c.add(0, 1, 0);
        return c.getBlockY();
    }

    private void summon(World w, Location loc) {
        int max = (int) num("sbires-max");
        if (minions.size() >= max) return;
        int n = Math.min(max - minions.size(), 2 + phase * 2 + Math.min(4, players / 2));
        for (int i = 0; i < n; i++) {
            Location l = loc.clone().add(ThreadLocalRandom.current().nextDouble(-5, 5), 0.5, ThreadLocalRandom.current().nextDouble(-5, 5));
            if (!l.getBlock().isPassable()) l = loc.clone().add(0, 0.5, 0);
            Class<? extends Entity> kind = switch (phase) {
                case 0 -> i % 2 == 0 ? Zombie.class : Skeleton.class;
                case 1 -> i % 3 == 0 ? Vex.class : (i % 2 == 0 ? WitherSkeleton.class : Skeleton.class);
                default -> i % 3 == 0 ? Blaze.class : (i % 3 == 1 ? WitherSkeleton.class : Vex.class);
            };
            Entity m = w.spawn(l, kind, this::tagMinion);
            if (m instanceof Mob mob) {
                Player t = nearest(32);
                if (t != null) mob.setTarget(t);
            }
            minions.add(m);
            w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.6, 0.3, 0.01);
        }
        say("<gray>invoque des renforts !");
    }

    private void say(String mini) {
        Component msg = plugin.text().parse(cfg().getString("name", "Le Colosse")).append(plugin.text().parse(" " + mini));
        for (Player p : around(48)) p.sendActionBar(msg);
    }

    /** Rappel aux admins : sans arène, le boss n'apparaît jamais. */
    @EventHandler
    public void onAdminJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("lyonmc.admin") || !plugin.module("boss") || cfg() == null || !cfg().getBoolean("enabled", true) || arena() != null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && arena() == null) {
                plugin.msg(p, "<red>⚠</red> <gray>Le boss mondial n'a pas d'arène : il n'apparaîtra jamais. Va dans le monde survie et tape <white>/boss arene</white>.");
            }
        }, 100L);
    }

    /** Les coups des joueurs entament la vraie vie du boss ; le reste ne lui fait rien. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamaged(org.bukkit.event.entity.EntityDamageEvent event) {
        if (boss == null || !event.getEntity().equals(boss)) return;
        Player attacker = null;
        boolean ranged = false;
        if (event instanceof EntityDamageByEntityEvent by) {
            if (by.getDamager() instanceof Player p) attacker = p;
            else if (by.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
                attacker = p;
                ranged = true;
            } else if (by.getDamager() instanceof Tameable tame && tame.getOwner() instanceof Player p) attacker = p;
            else if (by.getDamager() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) attacker = p;
        }
        if (attacker == null) {
            event.setCancelled(true);
            return;
        }
        if (shield) {
            event.setCancelled(true);
            attacker.playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
            attacker.sendActionBar(plugin.text().parse("<light_purple>Bouclier actif</light_purple> <gray>: tue d'abord les gardiens !"));
            return;
        }
        double dealt = event.getFinalDamage();
        if (ranged) dealt *= num("degats-distance") / 100.0;
        dealt = Math.min(dealt, hp);
        damage.merge(attacker.getUniqueId(), dealt, Double::sum);
        hp -= dealt;
        if (hp <= 0.5) {
            hp = 0;
            event.setDamage(boss.getHealth() + 1000); // coup fatal : la mort normale crédite le tueur
            return;
        }
        boss.setHealth(Math.max(1, Math.min(1024, 1024 * hp / maxHp)));
        event.setDamage(0);
        checkPhase();
    }

    /** Le boss a touché un joueur : il n'est pas coincé. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossHits(EntityDamageByEntityEvent event) {
        if (boss != null && event.getDamager().equals(boss) && event.getEntity() instanceof Player) lastBossHit = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionTarget(EntityTargetEvent event) {
        // Les sbires n'attaquent pas le boss et le boss n'attaque pas ses sbires
        if (event.getTarget() == null) return;
        boolean fromMinion = event.getEntity().getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)
                || event.getEntity().getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
        boolean toAlly = event.getTarget().getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                || event.getTarget().getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE);
        if (fromMinion && toAlly) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Byte kind = dead.getPersistentDataContainer().get(minionKey, PersistentDataType.BYTE);
        if (kind != null) {
            event.getDrops().clear();
            event.setDroppedExp(dead.getKiller() != null ? (int) num(kind == 2 ? "xp-gardien" : "xp-sbire") : 0);
            return;
        }
        if (boss == null || !dead.equals(boss)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player killer = dead.getKiller();
        Location loc = dead.getLocation();
        dropXp(loc, (int) num("xp-boss"));
        reward(killer);
        firework(loc);
        end(true, null);
    }

    private void reward(Player lastHit) {
        double total = 0;
        for (double d : damage.values()) total += d;
        List<Map.Entry<UUID, Double>> ranking = new ArrayList<>(damage.entrySet());
        ranking.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        double min = cfg().getDouble("min-damage-percent", 2);

        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(plugin.text().parse(cfg().getString("name", "Le Colosse")))
                .append(plugin.text().parse(" <gray>a été vaincu !")));
        String[] medals = {"<#ffd86b>♛ 1</#ffd86b>", "<#c8c8c8>✧ 2</#c8c8c8>", "<#d29a6a>✧ 3</#d29a6a>"};
        StringBuilder discord = new StringBuilder();
        for (int i = 0; i < ranking.size(); i++) {
            UUID u = ranking.get(i).getKey();
            double pct = total <= 0 ? 0 : ranking.get(i).getValue() * 100 / total;
            Profile p = plugin.profiles().get(u);
            if (p == null) continue;
            if (i < 3) {
                Bukkit.broadcast(plugin.text().parse("  " + medals[i] + " <white>" + p.name + "</white> <dark_gray>"
                        + String.format(Locale.FRANCE, "%.0f", pct) + " % des dégâts"));
                discord.append(i + 1).append(". ").append(p.name).append(" — ").append(String.format(Locale.FRANCE, "%.0f", pct)).append(" %\n");
            }
            if (pct < min) continue;
            p.bossKills++;
            p.dirty();
            give(p, "boss.rewards.participation");
            if (i < 3) give(p, "boss.rewards.top" + (i + 1));
            plugin.custom().grantLater(p, "colosse");
            Player online = Bukkit.getPlayer(u);
            if (online != null) {
                plugin.msg(online, "<gray>Ta part : <white>" + String.format(Locale.FRANCE, "%.1f", pct) + " %</white> des dégâts.");
                plugin.rewards().check(online);
            }
        }
        if (lastHit != null) {
            Profile p = plugin.profiles().get(lastHit);
            p.bossLastHits++;
            p.dirty();
            give(p, "boss.rewards.last-hit");
            plugin.custom().grant(lastHit, "coup_de_grace");
            Bukkit.broadcast(plugin.text().parse("  <gray>Coup de grâce : <white>" + lastHit.getName()));
        }
        plugin.discord().send("Boss vaincu !", discord.isEmpty() ? "Le boss mondial a été vaincu." : discord.toString(), 0x7BD88F,
                ranking.isEmpty() ? null : ranking.get(0).getKey());
    }

    private void give(Profile p, String path) {
        Player online = Bukkit.getPlayer(p.uuid());
        Bundle b = Bundle.of(plugin.configs().events().getConfigurationSection(path));
        if (b.isEmpty()) return;
        if (online != null) plugin.rewards().grant(online, b);
        else {
            p.pendingBundles.add(path);
            p.dirty();
        }
    }

    /** Une pluie d'orbes d'expérience autour du corps du boss. */
    private void dropXp(Location loc, int total) {
        if (total <= 0) return;
        World w = loc.getWorld();
        int orbs = Math.min(60, Math.max(1, total / 60));
        int each = total / orbs;
        for (int i = 0; i < orbs; i++) {
            Location l = loc.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 1, ThreadLocalRandom.current().nextDouble(-3, 3));
            w.spawn(l, ExperienceOrb.class, o -> o.setExperience(each));
        }
    }

    private void firework(Location loc) {
        World w = loc.getWorld();
        for (int i = 0; i < 4; i++) {
            Location l = loc.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 1, ThreadLocalRandom.current().nextDouble(-2, 2));
            w.spawn(l, Firework.class, fw -> {
                org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.fromRGB(0xFFD86B), Color.fromRGB(0xFF6B6B)).withFade(Color.WHITE).flicker(true).build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
            });
        }
    }

    public void end(boolean killed, String message) {
        if (bar != null) {
            for (UUID u : viewers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
            }
        }
        viewers.clear();
        for (Entity m : minions) if (m.isValid()) m.remove();
        minions.clear();
        for (Entity g : guardians) if (g.isValid()) g.remove();
        guardians.clear();
        shield = false;
        if (boss != null && boss.isValid() && !killed) boss.remove();
        boss = null;
        bar = null;
        damage.clear();
        if (message != null) {
            Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> " + message));
        }
    }
}
