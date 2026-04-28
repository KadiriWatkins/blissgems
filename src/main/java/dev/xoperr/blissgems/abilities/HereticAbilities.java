package dev.xoperr.blissgems.abilities;

import dev.xoperr.blissgems.BlissGems;
import dev.xoperr.blissgems.api.GemAbilityHandler;
import dev.xoperr.blissgems.utils.ParticleUtils;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Heretic Gem Abilities
 *
 * Passives:
 *   - Hemorrhage:        If the user gains Strength, its duration is greatly prolonged (up to 8m).
 *   - Enduring Strength: (linked to Hemorrhage — the passive pair name)
 *   - Auto-Crit:         Hits always critically strike, ignoring movement state and damage cooldown.
 *   - Blood Hardening:   TODO — placeholder for future implementation.
 *
 * Abilities:
 *   - Bloodsaws (Primary):   Fire 2 wide ricocheting projectiles that pass through entities.
 *                            If both are fired within 0.5s, a "spore" fountain AOE is triggered
 *                            that applies a bleeding amplifier effect to nearby players.
 *   - Bloodlinking (Secondary): Leap into the air and crash down, dealing AOE damage.
 *                            Players hit together are bloodlinked — damage dealt to one is
 *                            mirrored onto the other.
 */
public class HereticAbilities implements GemAbilityHandler {

    // ── Metadata keys ────────────────────────────────────────────────────────
    private static final String META_BLOODSAW        = "heretic_bloodsaw";
    private static final String META_BLEEDING        = "heretic_bleeding";

    // ── Bloodsaws rapid-fire window (ticks) ──────────────────────────────────
    private static final long DOUBLE_SHOT_WINDOW_TICKS = 10L; // 0.5 s

    // ── Heretic crimson colour ────────────────────────────────────────────────
    public static final Color HERETIC_CRIMSON = Color.fromRGB(139, 0, 0);
    public static final Color HERETIC_DARK    = Color.fromRGB(80, 0, 10);

    // ── Per-player state ──────────────────────────────────────────────────────
    /** Tracks the tick at which the first bloodsaw of a pair was fired. */
    private final Map<UUID, Long>      firstSawTick    = new HashMap<>();
    /** Bloodlink pairs: damageKey → reflectTarget */
    private final Map<UUID, UUID>      bloodlinks      = new HashMap<>();
    private final Map<UUID, BukkitTask> bloodlinkTasks = new HashMap<>();
    /** Tracks players currently in the Bloodlinking leap phase. */
    private final Set<UUID>            leapingPlayers  = new HashSet<>();

    private final BlissGems plugin;

    public HereticAbilities(BlissGems plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    //  GemAbilityHandler routing
    // =========================================================================

    @Override
    public void onPrimary(Player player, int tier) {
        bloodsaws(player);
    }

    @Override
    public void onSecondary(Player player, int tier) {
        bloodlinking(player);
    }

    @Override
    public void onTertiary(Player player, int tier) {
        // Reserved for future Blood Hardening active, if added.
    }

    // =========================================================================
    //  BLOODSAWS
    // =========================================================================

    public void bloodsaws(Player player) {
        String abilityKey = "heretic-bloodsaws";
        if (!plugin.getAbilityManager().canUseAbility(player, abilityKey)) return;

        UUID uuid = plugin.getServer().getCurrentTick() >= 0 ? player.getUniqueId() : null;
        if (uuid == null) return;

        long currentTick = plugin.getServer().getCurrentTick();
        Long firstTick   = firstSawTick.get(uuid);
        boolean isDouble = firstTick != null && (currentTick - firstTick) <= DOUBLE_SHOT_WINDOW_TICKS;

        // Fire two wide projectiles
        fireSawProjectile(player, -15);
        fireSawProjectile(player,  15);

        // Visual + sound
        Particle.DustOptions dust = new Particle.DustOptions(HERETIC_CRIMSON, 1.8f);
        player.getWorld().spawnParticle(Particle.DUST, player.getEyeLocation(), 20, 0.3, 0.2, 0.3, 0, dust, true);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
        player.playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.6f, 1.4f);

        if (isDouble) {
            // Both shots fired quickly — trigger spore fountain
            firstSawTick.remove(uuid);
            plugin.getAbilityManager().useAbility(player, abilityKey);
            triggerSporeFountain(player);
        } else {
            // Record this as the first shot; don't consume cooldown yet
            firstSawTick.put(uuid, currentTick);
            // Auto-expire window: if second shot isn't fired in time, consume cooldown then
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (firstSawTick.containsKey(uuid)) {
                    firstSawTick.remove(uuid);
                    plugin.getAbilityManager().useAbility(player, abilityKey);
                }
            }, DOUBLE_SHOT_WINDOW_TICKS + 1);
        }
    }

    private void fireSawProjectile(Player player, float yawOffset) {
        Location eye = player.getEyeLocation().clone();

        // Apply horizontal spread
        float newYaw = eye.getYaw() + yawOffset;
        eye.setYaw(newYaw);
        Vector dir = eye.getDirection().normalize();

        // Use a snowball as the projectile vehicle
        Snowball proj = player.getWorld().spawn(eye, Snowball.class, sb -> {
            sb.setShooter(player);
            sb.setVelocity(dir.multiply(2.2));
            sb.setMetadata(META_BLOODSAW, new FixedMetadataValue(plugin, true));
        });

        // Particle trail + ricochet logic
        new BukkitRunnable() {
            private int ticks = 0;
            private int bounces = 0;
            private static final int MAX_BOUNCES = 3;
            private static final int MAX_TICKS   = 80; // 4 seconds hard cap

            @Override
            public void run() {
                if (!proj.isValid() || ticks++ > MAX_TICKS) {
                    proj.remove();
                    cancel();
                    return;
                }

                Location loc = proj.getLocation();
                Particle.DustOptions trail = new Particle.DustOptions(HERETIC_CRIMSON, 1.2f);
                loc.getWorld().spawnParticle(Particle.DUST, loc, 5, 0.15, 0.15, 0.15, 0, trail, true);
                loc.getWorld().spawnParticle(Particle.CRIMSON_SPORE, loc, 3, 0.1, 0.1, 0.1);

                // Pass-through damage: hurt entities along path each tick (not on bounce)
                for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 0.8, 0.8, 0.8)) {
                    if (!(nearby instanceof LivingEntity) || nearby == player) continue;
                    if (nearby instanceof Player p2 && plugin.getTrustedPlayersManager().isTrusted(player, p2)) continue;
                    double sawDamage = plugin.getConfig().getDouble("abilities.heretic-bloodsaws.pass-through-damage", 3.0);
                    ((LivingEntity) nearby).damage(sawDamage, player);
                    // Subtle splash
                    loc.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, loc, 4, 0.2, 0.2, 0.2);
                }

                // Simple block-face ricochet: if velocity near zero on any axis, flip it
                Vector vel = proj.getVelocity();
                boolean bounced = false;
                if (Math.abs(vel.getX()) < 0.15) { vel.setX(-vel.getX() * 0.8); bounced = true; }
                if (Math.abs(vel.getZ()) < 0.15) { vel.setZ(-vel.getZ() * 0.8); bounced = true; }
                if (Math.abs(vel.getY()) < 0.15) { vel.setY(Math.abs(vel.getY()) * 0.6); bounced = true; }

                if (bounced) {
                    proj.setVelocity(vel);
                    bounces++;
                    loc.getWorld().playSound(loc, Sound.BLOCK_STONE_HIT, 0.5f, 1.8f);
                    if (bounces >= MAX_BOUNCES) {
                        proj.remove();
                        cancel();
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Spore fountain: visible upward particle burst at the player's location.
     * Nearby players receive a "bleeding" amplifier tag — any damage they take
     * is multiplied while the effect persists.
     */
    private void triggerSporeFountain(Player caster) {
        Location origin = caster.getLocation().add(0, 0.1, 0);
        double radius   = plugin.getConfig().getDouble("abilities.heretic-bloodsaws.spore-radius", 5.0);
        int    duration = plugin.getConfig().getInt("abilities.heretic-bloodsaws.spore-duration-ticks", 100);

        // Fountain particles
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t++ > 15) { cancel(); return; }
                for (int i = 0; i < 12; i++) {
                    double angle  = Math.random() * 2 * Math.PI;
                    double spread = Math.random() * radius * 0.4;
                    Location p = origin.clone().add(
                            Math.cos(angle) * spread,
                            t * 0.35,
                            Math.sin(angle) * spread
                    );
                    Particle.DustOptions d = new Particle.DustOptions(HERETIC_CRIMSON, 1.5f);
                    origin.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, d, true);
                    origin.getWorld().spawnParticle(Particle.CRIMSON_SPORE, p, 2, 0.05, 0.05, 0.05);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        origin.getWorld().playSound(origin, Sound.BLOCK_FUNGUS_HIT, 1.2f, 0.7f);
        origin.getWorld().playSound(origin, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 1.2f);

        // Apply bleeding to nearby players
        for (Entity e : origin.getWorld().getNearbyEntities(origin, radius, radius, radius)) {
            if (!(e instanceof Player target)) continue;
            if (target == caster) continue;
            if (plugin.getTrustedPlayersManager().isTrusted(caster, target)) continue;

            target.setMetadata(META_BLEEDING, new FixedMetadataValue(plugin, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, duration, 0, false, false));
            target.sendMessage("§4§oYou are bleeding! Damage taken is amplified!");

            // Remove tag after duration
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (target.isOnline()) target.removeMetadata(META_BLEEDING, plugin);
            }, duration);

            Particle.DustOptions d = new Particle.DustOptions(HERETIC_CRIMSON, 1.2f);
            target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, 1, 0),
                    30, 0.5, 0.8, 0.5, 0, d, true);
        }

        caster.sendMessage("§4§l⚗ Spore Fountain! §4Nearby players are bleeding!");
    }

    /**
     * Called from PassiveListener: amplify damage if the victim is bleeding.
     * Returns the modified damage value, or the original if not bleeding.
     */
    public double applyBleedingAmplification(LivingEntity victim, double baseDamage) {
        if (!victim.hasMetadata(META_BLEEDING)) return baseDamage;
        double mult = plugin.getConfig().getDouble("abilities.heretic-bloodsaws.bleed-amplifier", 1.35);
        return baseDamage * mult;
    }

    /** Returns true if this entity has the bleeding tag. */
    public boolean isBleeding(LivingEntity entity) {
        return entity.hasMetadata(META_BLEEDING);
    }

    // =========================================================================
    //  BLOODLINKING
    // =========================================================================

    public void bloodlinking(Player player) {
        String abilityKey = "heretic-bloodlinking";
        if (!plugin.getAbilityManager().canUseAbility(player, abilityKey)) return;
        if (leapingPlayers.contains(player.getUniqueId())) return;

        leapingPlayers.add(player.getUniqueId());

        // Phase 1: Leap upward
        Vector leap = new Vector(0, plugin.getConfig().getDouble("abilities.heretic-bloodlinking.leap-velocity", 1.6), 0);
        player.setVelocity(leap);

        Particle.DustOptions dust = new Particle.DustOptions(HERETIC_CRIMSON, 1.5f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0),
                30, 0.5, 0.5, 0.5, 0, dust, true);
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.4f);

        player.sendMessage("§4§l⬆ §4Bloodlinking — airborne!");

        // Phase 2: After apex, slam down
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { leapingPlayers.remove(player.getUniqueId()); return; }

            // Apply downward velocity for slam
            double slamVel = plugin.getConfig().getDouble("abilities.heretic-bloodlinking.slam-velocity", -2.8);
            player.setVelocity(new Vector(player.getVelocity().getX(), slamVel, player.getVelocity().getZ()));
            player.sendMessage("§4§l⬇ Slamming down!");

        }, plugin.getConfig().getInt("abilities.heretic-bloodlinking.apex-ticks", 14));

        // Phase 3: Detect landing — poll until on ground
        new BukkitRunnable() {
            int waited = 0;
            @Override public void run() {
                if (!player.isOnline() || waited++ > 60) {
                    leapingPlayers.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                if (player.isOnGround() && waited > 10) {
                    leapingPlayers.remove(player.getUniqueId());
                    onBloodlinkLand(player);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 18L, 1L);

        plugin.getAbilityManager().useAbility(player, abilityKey);
    }

    private void onBloodlinkLand(Player player) {
        Location impact   = player.getLocation();
        double   radius   = plugin.getConfig().getDouble("abilities.heretic-bloodlinking.aoe-radius", 4.0);
        double   damage   = plugin.getConfig().getDouble("abilities.heretic-bloodlinking.impact-damage", 5.0);
        int      linkTicks = plugin.getConfig().getInt("abilities.heretic-bloodlinking.link-duration-ticks", 200);

        // Impact effects
        Particle.DustOptions dust = new Particle.DustOptions(HERETIC_CRIMSON, 2.0f);
        impact.getWorld().spawnParticle(Particle.DUST, impact, 60, radius * 0.5, 0.2, radius * 0.5, 0, dust, true);
        impact.getWorld().spawnParticle(Particle.BLOCK, impact, 40, radius * 0.4, 0.3, radius * 0.4, 0.15,
                Material.NETHER_WART_BLOCK.createBlockData());
        impact.getWorld().spawnParticle(Particle.CRIMSON_SPORE, impact, 50, radius * 0.6, 0.5, radius * 0.6);
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.8f);
        impact.getWorld().playSound(impact, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.5f);

        // Draw expanding circle
        ParticleUtils.drawExpandingCircles(impact, HERETIC_CRIMSON, 1.4f, radius, null);

        // Damage and collect hit players
        List<Player> hit = new ArrayList<>();
        for (Entity e : impact.getWorld().getNearbyEntities(impact, radius, radius * 0.8, radius)) {
            if (!(e instanceof Player target)) continue;
            if (target == player) continue;
            if (plugin.getTrustedPlayersManager().isTrusted(player, target)) continue;
            target.damage(damage, player);
            hit.add(target);
        }

        // Bloodlink the first two distinct hit players
        if (hit.size() >= 2) {
            Player a = hit.get(0);
            Player b = hit.get(1);
            createBloodlink(a, b, linkTicks);
            player.sendMessage("§4§l⛓ §4Bloodlinked §c" + a.getName() + " §4↔ §c" + b.getName() + "!");
            a.sendMessage("§4§oYou are Bloodlinked to §c" + b.getName() + "§4! Damage is shared!");
            b.sendMessage("§4§oYou are Bloodlinked to §c" + a.getName() + "§4! Damage is shared!");
        } else if (hit.size() == 1) {
            // Link the single hit player to the caster
            createBloodlink(player, hit.get(0), linkTicks);
            player.sendMessage("§4§l⛓ §4Bloodlinked to §c" + hit.get(0).getName() + "!");
            hit.get(0).sendMessage("§4§oYou are Bloodlinked to §c" + player.getName() + "§4! Damage is shared!");
        } else {
            player.sendMessage("§4§oNo targets caught in the crash.");
        }
    }

    private void createBloodlink(Player a, Player b, int durationTicks) {
        UUID uA = a.getUniqueId();
        UUID uB = b.getUniqueId();

        // Cancel any existing links for either player
        clearBloodlink(uA);
        clearBloodlink(uB);

        bloodlinks.put(uA, uB);
        bloodlinks.put(uB, uA);

        // Particle tether between them
        BukkitTask task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player pA = Bukkit.getPlayer(uA);
                Player pB = Bukkit.getPlayer(uB);
                if (pA == null || !pA.isOnline() || pB == null || !pB.isOnline() || t++ > durationTicks) {
                    clearBloodlink(uA);
                    clearBloodlink(uB);
                    cancel();
                    return;
                }
                if (t % 4 == 0) {
                    ParticleUtils.drawColoredLine(
                            pA.getLocation().add(0, 1, 0),
                            pB.getLocation().add(0, 1, 0),
                            HERETIC_CRIMSON, 1.0f, 6
                    );
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        bloodlinkTasks.put(uA, task);
    }

    private void clearBloodlink(UUID uuid) {
        bloodlinks.remove(uuid);
        BukkitTask t = bloodlinkTasks.remove(uuid);
        if (t != null) t.cancel();
    }

    /**
     * Called from PassiveListener on EntityDamageByEntityEvent.
     * If the victim is bloodlinked, mirror the damage to their linked partner.
     */
    public void handleBloodlinkDamage(Player victim, double damage) {
        UUID partnerUUID = bloodlinks.get(victim.getUniqueId());
        if (partnerUUID == null) return;
        Player partner = Bukkit.getPlayer(partnerUUID);
        if (partner == null || !partner.isOnline()) {
            clearBloodlink(victim.getUniqueId());
            return;
        }
        // Mirror damage (slightly reduced to avoid infinite loop potential)
        double mirror = damage * plugin.getConfig().getDouble("abilities.heretic-bloodlinking.mirror-ratio", 0.75);
        partner.damage(mirror);
        Particle.DustOptions d = new Particle.DustOptions(HERETIC_CRIMSON, 1.2f);
        partner.getWorld().spawnParticle(Particle.DUST, partner.getLocation().add(0, 1, 0),
                20, 0.4, 0.6, 0.4, 0, d, true);
        partner.sendMessage("§4§oBloodlink transferred §c" + String.format("%.1f", mirror / 2) + "§4 hearts to you!");
    }

    /** Returns true if this player is currently bloodlinked. */
    public boolean isBloodlinked(Player player) {
        return bloodlinks.containsKey(player.getUniqueId());
    }

    // =========================================================================
    //  Passive helpers (called from PassiveListener)
    // =========================================================================

    /**
     * Hemorrhage / Enduring Strength:
     * If a Heretic gem holder receives the Strength effect, greatly extend its duration.
     * Call this from PassiveListener on PlayerItemConsumeEvent or PotionEffectAddEvent.
     */
    public void applyHemorrhage(Player player, PotionEffect effect) {
        if (effect.getType() != PotionEffectType.STRENGTH) return;
        int maxDuration  = plugin.getConfig().getInt("passives.heretic.hemorrhage.max-duration-ticks", 9600); // 8 min
        int newDuration  = Math.min(effect.getDuration() * 4, maxDuration);
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, newDuration, effect.getAmplifier(), false, true));
        player.sendMessage("§4§oEnduring Strength — duration extended to §c"
                + (newDuration / 20) + "s§4!");
    }

    // =========================================================================
    //  Cleanup
    // =========================================================================

    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        firstSawTick.remove(uuid);
        leapingPlayers.remove(uuid);
        clearBloodlink(uuid);
    }
}
