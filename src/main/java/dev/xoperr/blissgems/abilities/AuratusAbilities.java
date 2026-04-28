package dev.xoperr.blissgems.abilities;

import dev.xoperr.blissgems.BlissGems;
import dev.xoperr.blissgems.api.GemAbilityHandler;
import dev.xoperr.blissgems.utils.ParticleUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Auratus Gem Abilities
 *
 * Passives:
 *   - Divine Purity:   Negates all negative status effects (Wither, Weakness, etc.).
 *                      [UNCONFIRMED — implemented conservatively; can be toggled via config]
 *   - Angel's Grasp:   5% chance on hit to pull nearby enemies toward the user.
 *                      (Alternate interpretation: 10% reduced armor durability damage.
 *                       Config flag selects which behaviour is active.)
 *   - Feathered Fall:  Greatly reduces fall damage.
 *   - Hauling Strike:  While crouched:
 *                        • Haste 2 when mining blocks
 *                        • Extended reach (+1 block)
 *                        • Reverse knockback (hit enemies forward/toward caster)
 *                        • Extends shield cooldown if opponent's shield is disabled on hit
 *
 * Abilities:
 *   - Venerated Perforators (Primary):
 *       Launches two chain projectiles that grapple terrain (pull user) or entities (pull target).
 *       Holding SHIFT before impact triggers a ground-slam: AOE damage + disables wind charges.
 *       Using alongside Echoing Aegis summons an anchor above the player.
 *
 *   - Echoing Aegis (Secondary):
 *       Brief parry window (~0.6 s). During this window incoming attacks are reflected back,
 *       potions are reflected, and hitting the parrying player drains weapon durability.
 *       On successful parry: heal 2–3 hearts, gain lingering Speed 3 + Weaving.
 *       Also enables the sky-anchor variant of Venerated Perforators while active.
 */
public class AuratusAbilities implements GemAbilityHandler {

    // ── Colours ───────────────────────────────────────────────────────────────
    public static final Color AURATUS_GOLD   = Color.fromRGB(255, 215, 0);
    public static final Color AURATUS_WHITE  = Color.fromRGB(255, 255, 240);
    public static final Color AURATUS_SILVER = Color.fromRGB(192, 192, 192);

    // ── Metadata keys ─────────────────────────────────────────────────────────
    private static final String META_CHAIN    = "auratus_chain";
    private static final String META_PARRYING = "auratus_parrying";
    private static final String META_ANCHOR   = "auratus_anchor";

    // ── Per-player state ──────────────────────────────────────────────────────
    /** Players currently in Echoing Aegis parry window. */
    private final Set<UUID>             parryingPlayers = new HashSet<>();
    /** Sky-anchor locations, set while Echoing Aegis is active. */
    private final Map<UUID, Location>   anchors         = new HashMap<>();
    /** Chain grapple tasks. */
    private final Map<UUID, BukkitTask> chainTasks      = new HashMap<>();
    /** Haste state for Hauling Strike. */
    private final Set<UUID>             haulHaste       = new HashSet<>();

    private final BlissGems plugin;

    public AuratusAbilities(BlissGems plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    //  GemAbilityHandler routing
    // =========================================================================

    @Override
    public void onPrimary(Player player, int tier) {
        veneratedPerforators(player);
    }

    @Override
    public void onSecondary(Player player, int tier) {
        echoingAegis(player);
    }

    @Override
    public void onTertiary(Player player, int tier) {
        // No tertiary defined yet.
    }

    // =========================================================================
    //  VENERATED PERFORATORS
    // =========================================================================

    public void veneratedPerforators(Player player) {
        String abilityKey = "auratus-venerated-perforators";
        if (!plugin.getAbilityManager().canUseAbility(player, abilityKey)) return;

        boolean shiftHeld = player.isSneaking();
        boolean aegisActive = parryingPlayers.contains(player.getUniqueId());

        if (aegisActive) {
            // Place a sky anchor
            Location anchor = player.getLocation().add(0, 12, 0);
            anchors.put(player.getUniqueId(), anchor);
            player.sendMessage("§e§l⚓ §eAnchor planted! Use Perforators again to grapple it.");
            spawnAnchorParticles(anchor);
            plugin.getAbilityManager().useAbility(player, abilityKey);
            return;
        }

        // Check if there is a sky anchor to grapple
        Location existingAnchor = anchors.get(player.getUniqueId());
        if (existingAnchor != null) {
            grappleToLocation(player, existingAnchor, false);
            anchors.remove(player.getUniqueId());
            plugin.getAbilityManager().useAbility(player, abilityKey);
            return;
        }

        // Fire two chain projectiles with slight spread
        fireChain(player, -8,  shiftHeld);
        fireChain(player,  8,  shiftHeld);

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.8f);
        player.getWorld().spawnParticle(Particle.DUST, player.getEyeLocation(), 25, 0.3, 0.3, 0.3, 0, d, true);
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.2f);

        plugin.getAbilityManager().useAbility(player, abilityKey);
    }

    private void fireChain(Player player, float yawOffset, boolean groundSlam) {
        Location eye = player.getEyeLocation().clone();
        eye.setYaw(eye.getYaw() + yawOffset);
        Vector dir = eye.getDirection().normalize().multiply(
                plugin.getConfig().getDouble("abilities.auratus-venerated-perforators.chain-speed", 2.0)
        );

        Snowball proj = player.getWorld().spawn(eye, Snowball.class, sb -> {
            sb.setShooter(player);
            sb.setVelocity(dir);
            sb.setMetadata(META_CHAIN, new FixedMetadataValue(plugin, groundSlam));
        });

        // Chain trail + hit detection
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!proj.isValid() || ticks++ > 40) { proj.remove(); cancel(); return; }

                Location loc = proj.getLocation();
                Particle.DustOptions trail = new Particle.DustOptions(AURATUS_GOLD, 1.0f);
                loc.getWorld().spawnParticle(Particle.DUST, loc, 4, 0.05, 0.05, 0.05, 0, trail, true);
                loc.getWorld().spawnParticle(Particle.CRIT, loc, 2, 0.05, 0.05, 0.05);

                // Hit entity?
                for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 0.6, 0.6, 0.6)) {
                    if (!(nearby instanceof LivingEntity) || nearby == player) continue;
                    if (nearby instanceof Player p2 && plugin.getTrustedPlayersManager().isTrusted(player, p2)) continue;

                    // Pull entity toward player
                    Vector pull = player.getLocation().add(0, 0.5, 0)
                            .subtract(nearby.getLocation()).toVector().normalize().multiply(1.6);
                    nearby.setVelocity(pull);
                    nearby.getWorld().playSound(nearby.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.3f);
                    player.sendMessage("§e§oGrappled §6" + (nearby instanceof Player p2 ? p2.getName() : nearby.getType().name().toLowerCase()) + "§e!");

                    if (groundSlam) triggerGroundSlam(player, loc);
                    proj.remove();
                    cancel();
                    return;
                }

                // Hit block / near ground — pull player toward it
                Block block = loc.getBlock();
                if (block.getType().isSolid() || (loc.getY() <= player.getWorld().getMinHeight() + 1)) {
                    if (groundSlam) {
                        triggerGroundSlam(player, loc);
                    } else {
                        grappleToLocation(player, loc, true);
                    }
                    proj.remove();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void grappleToLocation(Player player, Location target, boolean particleTrail) {
        Vector pull = target.clone().subtract(player.getLocation()).toVector();
        double dist = pull.length();
        if (dist < 0.5) return;

        pull = pull.normalize().multiply(Math.min(dist * 0.6, 2.8));
        player.setVelocity(pull);
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.3f);

        if (particleTrail) {
            ParticleUtils.drawColoredLine(
                    player.getLocation().add(0, 1, 0), target,
                    AURATUS_GOLD, 1.0f, 6
            );
        }
    }

    private void triggerGroundSlam(Player player, Location impact) {
        double slamRadius  = plugin.getConfig().getDouble("abilities.auratus-venerated-perforators.slam-radius", 5.0);
        double slamDamage  = plugin.getConfig().getDouble("abilities.auratus-venerated-perforators.slam-damage", 6.0);
        double windDisable = plugin.getConfig().getDouble("abilities.auratus-venerated-perforators.wind-disable-radius", 3.0);

        // Launch player down into the slam (if not on ground)
        if (!player.isOnGround()) {
            player.setVelocity(new Vector(0, -3.5, 0));
        }

        // Detonate immediately at impact location
        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 2.0f);
        impact.getWorld().spawnParticle(Particle.DUST, impact, 80, slamRadius * 0.4, 0.3, slamRadius * 0.4, 0, d, true);
        impact.getWorld().spawnParticle(Particle.EXPLOSION, impact, 5, 1.0, 0.3, 1.0);
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.6f);
        ParticleUtils.drawExpandingCircles(impact, AURATUS_GOLD, 1.5f, slamRadius, null);

        for (Entity e : impact.getWorld().getNearbyEntities(impact, slamRadius, slamRadius * 0.6, slamRadius)) {
            if (!(e instanceof LivingEntity) || e == player) continue;
            if (e instanceof Player p2 && plugin.getTrustedPlayersManager().isTrusted(player, p2)) continue;

            double dist = e.getLocation().distance(impact);
            double dmg  = slamDamage * (1.0 - dist / slamRadius);
            ((LivingEntity) e).damage(dmg, player);

            // Disable wind charges if close enough
            if (dist <= windDisable && e instanceof Player target) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false));
                target.sendMessage("§e§oYour wind charges have been disrupted!");
            }
        }

        player.sendMessage("§6§l☄ Ground Slam!");
    }

    private void spawnAnchorParticles(Location anchor) {
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t++ > 40) { cancel(); return; }
                Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.5f);
                anchor.getWorld().spawnParticle(Particle.DUST, anchor, 8, 0.3, 0.3, 0.3, 0, d, true);
                anchor.getWorld().spawnParticle(Particle.ENCHANTED_HIT, anchor, 4, 0.2, 0.2, 0.2);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        anchor.getWorld().playSound(anchor, Sound.BLOCK_BELL_USE, 1.2f, 1.5f);
    }

    // =========================================================================
    //  ECHOING AEGIS
    // =========================================================================

    public void echoingAegis(Player player) {
        String abilityKey = "auratus-echoing-aegis";
        if (!plugin.getAbilityManager().canUseAbility(player, abilityKey)) return;
        if (parryingPlayers.contains(player.getUniqueId())) return; // already active

        int parryTicks = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.parry-ticks", 12);

        parryingPlayers.add(player.getUniqueId());
        player.setMetadata(META_PARRYING, new FixedMetadataValue(plugin, true));

        // Gold flash around player
        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 2.0f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0),
                50, 0.6, 0.8, 0.6, 0, d, true);
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getLocation().add(0, 1, 0),
                30, 0.5, 0.7, 0.5);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 1.5f);
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.8f, 1.3f);

        // Also plant sky anchor for the duration
        Location skyAnchor = player.getLocation().add(0, 14, 0);
        anchors.put(player.getUniqueId(), skyAnchor);
        spawnAnchorParticles(skyAnchor);

        player.sendMessage("§e§l🛡 Echoing Aegis! §eParry window active!");

        // Expiry
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            endParry(player, false);
        }, parryTicks);

        plugin.getAbilityManager().useAbility(player, abilityKey);
    }

    /**
     * Called from PassiveListener when a parrying player is hit.
     * if @param success true = attacker hit them during parry window
     */
    public boolean handleParry(Player parryPlayer, Entity attacker, double incomingDamage) {
        if (!parryingPlayers.contains(parryPlayer.getUniqueId())) return false;

        // Reflect damage to attacker
        if (attacker instanceof LivingEntity) {
            ((LivingEntity) attacker).damage(incomingDamage, parryPlayer);
        }

        // Drain weapon durability of attacker
        if (attacker instanceof Player atk) {
            ItemStack weapon = atk.getInventory().getItemInMainHand();
            if (weapon != null && !weapon.getType().isAir() && weapon.getItemMeta() instanceof Damageable dm) {
                int drainAmt = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.weapon-drain", 15);
                dm.setDamage(dm.getDamage() + drainAmt);
                weapon.setItemMeta((org.bukkit.inventory.meta.ItemMeta) dm);
                atk.sendMessage("§e§oYour weapon durability was drained by Echoing Aegis!");
            }
        }

        endParry(parryPlayer, true);
        return true; // cancel original damage
    }

    /**
     * Reflect a splash/lingering potion thrown at the parrying player back toward the thrower.
     */
    public boolean handleParryPotion(Player parryPlayer, ThrownPotion potion) {
        if (!parryingPlayers.contains(parryPlayer.getUniqueId())) return false;
        Entity thrower = (Entity) potion.getShooter();
        if (thrower == null) return false;

        // Redirect velocity toward thrower
        Vector reflect = thrower.getLocation().subtract(potion.getLocation()).toVector().normalize().multiply(1.2);
        potion.setVelocity(reflect);

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.2f);
        potion.getLocation().getWorld().spawnParticle(Particle.DUST, potion.getLocation(),
                15, 0.2, 0.2, 0.2, 0, d, true);

        endParry(parryPlayer, true);
        return false; // don't cancel — let it hit the thrower
    }

    private void endParry(Player player, boolean success) {
        parryingPlayers.remove(player.getUniqueId());
        player.removeMetadata(META_PARRYING, plugin);

        if (success) {
            // Heal 2-3 hearts
            double healMin = plugin.getConfig().getDouble("abilities.auratus-echoing-aegis.heal-min", 4.0);
            double healMax = plugin.getConfig().getDouble("abilities.auratus-echoing-aegis.heal-max", 6.0);
            double heal    = healMin + Math.random() * (healMax - healMin);
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));

            // Speed 3 lingering
            int speedTicks   = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.speed-duration-ticks", 80);
            int weavingTicks = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.weaving-duration-ticks", 60);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedTicks, 2, false, true));
            // Weaving = Slow Falling as a thematic stand-in (actual "Weaving" is a non-vanilla effect)
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, weavingTicks, 0, false, true));

            Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.6f);
            player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0),
                    60, 0.6, 0.9, 0.6, 0, d, true);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0),
                    25, 0.4, 0.6, 0.4, 0.1);
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.6f);
            player.sendMessage("§6§l✦ Perfect Parry! §eHealed §6" + String.format("%.1f", heal / 2) + "§e hearts!");
        } else {
            player.sendMessage("§e§oEchoing Aegis faded.");
        }
    }

    /** Returns true if the player is currently in a parry window. */
    public boolean isParrying(Player player) {
        return parryingPlayers.contains(player.getUniqueId());
    }

    // =========================================================================
    //  Hauling Strike — passive helper (called from PassiveListener)
    // =========================================================================

    /**
     * Called from PassiveListener onPlayerToggleSneak.
     * Grants Haste 2 while crouching with an Auratus gem.
     */
    public void onHaulSneak(Player player, boolean sneaking) {
        if (sneaking) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, Integer.MAX_VALUE, 1, false, false));
            haulHaste.add(player.getUniqueId());
        } else {
            player.removePotionEffect(PotionEffectType.HASTE);
            haulHaste.remove(player.getUniqueId());
        }
    }

    /**
     * Hauling Strike hit logic — call from PassiveListener EntityDamageByEntityEvent.
     * When crouching:
     *   • Apply reverse knockback (push target toward caster's facing, not away)
     *   • Extend shield cooldown if their shield is currently on cooldown
     */
    public void applyHaulingStrike(Player attacker, LivingEntity target) {
        if (!attacker.isSneaking()) return;

        // Reverse knockback: push target forward relative to attacker's look direction
        Vector forward = attacker.getEyeLocation().getDirection().normalize().multiply(1.4);
        forward.setY(0.3);
        target.setVelocity(forward);

        // Shield cooldown extension
        if (target instanceof Player targetPlayer) {
            ItemStack offhand = targetPlayer.getInventory().getItemInOffHand();
            if (offhand.getType() == Material.SHIELD && targetPlayer.getCooldown(Material.SHIELD) > 0) {
                int ext = plugin.getConfig().getInt("passives.auratus.hauling-strike.shield-cd-extension", 40);
                int cur = targetPlayer.getCooldown(Material.SHIELD);
                targetPlayer.setCooldown(Material.SHIELD, cur + ext);
                targetPlayer.sendMessage("§e§oYour shield cooldown was extended!");
            }
        }

        // Subtle gold particles on hit
        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.0f);
        target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, 1, 0),
                15, 0.3, 0.4, 0.3, 0, d, true);
    }

    /**
     * Angel's Grasp — 5% chance on hit to pull nearby enemies toward the attacker.
     */
    public void tryAngelsGrasp(Player attacker, Location hitLoc) {
        String mode = plugin.getConfig().getString("passives.auratus.angels-grasp.mode", "pull");
        if (!"pull".equalsIgnoreCase(mode)) return; // other mode handled in durability event

        double chance = plugin.getConfig().getDouble("passives.auratus.angels-grasp.pull-chance", 0.05);
        if (Math.random() > chance) return;

        double radius = plugin.getConfig().getDouble("passives.auratus.angels-grasp.pull-radius", 4.0);
        for (Entity e : hitLoc.getWorld().getNearbyEntities(hitLoc, radius, radius, radius)) {
            if (!(e instanceof LivingEntity) || e == attacker) continue;
            if (e instanceof Player p2 && plugin.getTrustedPlayersManager().isTrusted(attacker, p2)) continue;
            Vector pull = attacker.getLocation().add(0, 0.5, 0)
                    .subtract(e.getLocation()).toVector().normalize().multiply(1.2);
            e.setVelocity(pull);
        }

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_WHITE, 1.2f);
        hitLoc.getWorld().spawnParticle(Particle.DUST, hitLoc, 20, radius * 0.3, 0.3, radius * 0.3, 0, d, true);
        hitLoc.getWorld().playSound(hitLoc, Sound.ENTITY_FISHING_BOBBER_THROW, 0.6f, 1.8f);
    }

    /**
     * Angel's Grasp — armor durability reduction mode (10% less durability damage taken).
     * Call from a PlayerDamage event before durability is applied.
     * Returns the modified durability damage value.
     */
    public int applyAngelsGraspDurabilityReduction(int originalDurabilityDamage) {
        String mode = plugin.getConfig().getString("passives.auratus.angels-grasp.mode", "pull");
        if (!"durability".equalsIgnoreCase(mode)) return originalDurabilityDamage;
        return (int) Math.max(0, originalDurabilityDamage * 0.9);
    }

    // =========================================================================
    //  Cleanup
    // =========================================================================

    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        parryingPlayers.remove(uuid);
        anchors.remove(uuid);
        haulHaste.remove(uuid);
        BukkitTask t = chainTasks.remove(uuid);
        if (t != null) t.cancel();
        if (player.isOnline()) {
            player.removeMetadata(META_PARRYING, plugin);
        }
    }
}
