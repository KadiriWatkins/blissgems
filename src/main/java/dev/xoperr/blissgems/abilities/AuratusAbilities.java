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
 *   - Divine Purity:   Clears negative status effects when hit.
 *   - Angel's Grasp:   5% chance on hit to pull nearby enemies toward the user.
 *   - Feathered Fall:  Greatly reduces fall damage.
 *   - Hauling Strike:  While crouched: Haste 2, reverse knockback, extends shield cooldown.
 *
 * Abilities:
 *   - Venerated Perforators (Primary):
 *       Two charges. Each press fires ONE hook toward your crosshair.
 *         • Hits a BLOCK  → grapples the player toward it with added upward momentum.
 *         • Hits an ENTITY → pulls that entity toward the player.
 *       Charges refill independently on a per-charge cooldown.
 *
 *   - Echoing Aegis (Secondary):
 *       Brief parry window (~0.6 s). Reflects attacks & potions, drains weapon durability.
 *       On success: heal 2–3 hearts, Speed III + Slow Falling.
 */
public class AuratusAbilities implements GemAbilityHandler {

    // ── Colours ───────────────────────────────────────────────────────────────
    public static final Color AURATUS_GOLD   = Color.fromRGB(255, 215, 0);
    public static final Color AURATUS_WHITE  = Color.fromRGB(255, 255, 240);
    public static final Color AURATUS_SILVER = Color.fromRGB(192, 192, 192);

    // ── Metadata keys ─────────────────────────────────────────────────────────
    private static final String META_CHAIN    = "auratus_chain";
    private static final String META_PARRYING = "auratus_parrying";

    // ── Per-player state ──────────────────────────────────────────────────────
    /** Players currently in Echoing Aegis parry window. */
    private final Set<UUID>             parryingPlayers = new HashSet<>();
    /** Chain grapple tasks (one per in-flight hook). */
    private final Map<UUID, BukkitTask> chainTasks      = new HashMap<>();
    /** Haste state for Hauling Strike. */
    private final Set<UUID>             haulHaste       = new HashSet<>();

    /**
     * Venerated Perforators charges per player (0-2).
     * Starts at 2. Each use removes 1. Each charge refills after its cooldown.
     */
    private final Map<UUID, Integer>    charges         = new HashMap<>();
    /**
     * Tracks whether a hook is currently travelling for a player.
     * Prevents firing the second charge while the first is still in the air.
     */
    private final Set<UUID>             hookInFlight    = new HashSet<>();

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
        // Reserved.
    }

    // =========================================================================
    //  VENERATED PERFORATORS
    // =========================================================================

    public void veneratedPerforators(Player player) {
        UUID uuid = player.getUniqueId();

        // Initialise charges to max on first use
        charges.putIfAbsent(uuid, 2);

        int currentCharges = charges.get(uuid);

        if (currentCharges <= 0) {
            player.sendMessage("§e§oNo charges! Charges refill after a short cooldown.");
            return;
        }

        // Don't fire while a hook is already travelling — wait for it to land
        if (hookInFlight.contains(uuid)) {
            player.sendMessage("§e§oA hook is still in flight!");
            return;
        }

        // Consume one charge
        int remaining = currentCharges - 1;
        charges.put(uuid, remaining);

        // Show charge HUD
        String chargeBar = (remaining >= 2 ? "§e⛓⛓" : remaining == 1 ? "§e⛓§7⛓" : "§7⛓⛓");
        player.sendActionBar(net.kyori.adventure.text.Component.text("§6Perforators " + chargeBar));

        // Fire ONE hook straight toward the crosshair
        hookInFlight.add(uuid);
        fireHook(player);

        // Sound + launch particles
        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.8f);
        player.getWorld().spawnParticle(Particle.DUST, player.getEyeLocation(), 18, 0.2, 0.2, 0.2, 0, d, true);
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.2f);

        // Schedule charge refill
        int refillTicks = plugin.getConfig().getInt("abilities.auratus-venerated-perforators.charge-refill-ticks", 100);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int cur = charges.getOrDefault(uuid, 0);
            if (cur < 2) {
                charges.put(uuid, cur + 1);
                if (player.isOnline()) {
                    int newCur = charges.get(uuid);
                    String bar = (newCur >= 2 ? "§e⛓⛓" : "§e⛓§7⛓");
                    player.sendActionBar(net.kyori.adventure.text.Component.text("§6Perforators " + bar + " §7(charge ready)"));
                    player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 1.8f);
                }
            }
        }, refillTicks);
    }

    /**
     * Fire a single hook projectile straight in the player's look direction.
     * Block hit  → grapple player toward block with upward boost.
     * Entity hit → pull entity toward player.
     */
    private void fireHook(Player player) {
        UUID uuid = player.getUniqueId();
        Location eye = player.getEyeLocation().clone();
        double speed = plugin.getConfig().getDouble("abilities.auratus-venerated-perforators.chain-speed", 2.2);
        Vector dir = eye.getDirection().normalize().multiply(speed);

        Snowball proj = player.getWorld().spawn(eye, Snowball.class, sb -> {
            sb.setShooter(player);
            sb.setVelocity(dir);
            sb.setMetadata(META_CHAIN, new FixedMetadataValue(plugin, true));
        });

        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;
            final int maxRange = plugin.getConfig().getInt("abilities.auratus-venerated-perforators.max-range-ticks", 30);

            @Override
            public void run() {
                if (!proj.isValid() || ticks++ > maxRange) {
                    proj.remove();
                    hookInFlight.remove(uuid);
                    cancel();
                    return;
                }

                Location loc = proj.getLocation();

                // Particle trail
                Particle.DustOptions trail = new Particle.DustOptions(AURATUS_GOLD, 1.0f);
                loc.getWorld().spawnParticle(Particle.DUST, loc, 4, 0.05, 0.05, 0.05, 0, trail, true);
                loc.getWorld().spawnParticle(Particle.CRIT, loc, 2, 0.05, 0.05, 0.05);

                // ── Entity hit? ──────────────────────────────────────────────
                for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 0.7, 0.7, 0.7)) {
                    if (!(nearby instanceof LivingEntity) || nearby == player) continue;
                    if (nearby instanceof Player p2 && plugin.getTrustedPlayersManager().isTrusted(player, p2)) continue;

                    // Pull the entity toward the player
                    Vector pull = player.getLocation().add(0, 0.8, 0)
                            .subtract(nearby.getLocation()).toVector();
                    double dist = pull.length();
                    if (dist > 0.3) {
                        pull = pull.normalize().multiply(Math.min(dist * 0.55, 2.2));
                        nearby.setVelocity(pull);
                    }

                    // Visual chain line
                    ParticleUtils.drawColoredLine(
                            player.getLocation().add(0, 1, 0),
                            nearby.getLocation().add(0, 1, 0),
                            AURATUS_GOLD, 1.0f, 8
                    );

                    nearby.getWorld().playSound(nearby.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.3f);
                    player.sendMessage("§e§oHooked §6"
                            + (nearby instanceof Player p2 ? p2.getName() : nearby.getType().name().toLowerCase())
                            + "§e!");

                    proj.remove();
                    hookInFlight.remove(uuid);
                    cancel();
                    return;
                }

                // ── Block hit? ───────────────────────────────────────────────
                Block block = loc.getBlock();
                if (block.getType().isSolid()) {
                    grappleToBlock(player, loc);
                    proj.remove();
                    hookInFlight.remove(uuid);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        chainTasks.put(uuid, task);
    }

    /**
     * Pull the player toward the block that the hook hit, adding upward momentum.
     */
    private void grappleToBlock(Player player, Location hookLoc) {
        Vector pull = hookLoc.clone().subtract(player.getLocation()).toVector();
        double dist = pull.length();
        if (dist < 0.5) return;

        // Horizontal pull scaled by distance, capped so it isn't instant
        double horizontalStrength = plugin.getConfig().getDouble(
                "abilities.auratus-venerated-perforators.grapple-strength", 1.0);
        double upwardBoost = plugin.getConfig().getDouble(
                "abilities.auratus-venerated-perforators.grapple-upward-boost", 0.5);

        Vector velocity = pull.normalize().multiply(Math.min(dist * 0.5, 2.6) * horizontalStrength);
        // Always add upward boost so the player arcs toward the block rather than skimming the floor
        velocity.setY(velocity.getY() + upwardBoost);

        player.setVelocity(velocity);
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.2f);

        // Visual chain line from player to hook point
        ParticleUtils.drawColoredLine(
                player.getLocation().add(0, 1, 0), hookLoc,
                AURATUS_GOLD, 1.0f, 8
        );
    }

    // =========================================================================
    //  ECHOING AEGIS
    // =========================================================================

    public void echoingAegis(Player player) {
        String abilityKey = "auratus-echoing-aegis";
        if (!plugin.getAbilityManager().canUseAbility(player, abilityKey)) return;
        if (parryingPlayers.contains(player.getUniqueId())) return;

        int parryTicks = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.parry-ticks", 12);

        parryingPlayers.add(player.getUniqueId());
        player.setMetadata(META_PARRYING, new FixedMetadataValue(plugin, true));

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 2.0f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0),
                50, 0.6, 0.8, 0.6, 0, d, true);
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getLocation().add(0, 1, 0),
                30, 0.5, 0.7, 0.5);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 1.5f);
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.8f, 1.3f);

        player.sendMessage("§e§l🛡 Echoing Aegis! §eParry window active!");

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            endParry(player, false);
        }, parryTicks);

        plugin.getAbilityManager().useAbility(player, abilityKey);
    }

    /**
     * Called from PassiveListener when a parrying player is hit.
     * Returns true if the damage should be cancelled (parry succeeded).
     */
    public boolean handleParry(Player parryPlayer, Entity attacker, double incomingDamage) {
        if (!parryingPlayers.contains(parryPlayer.getUniqueId())) return false;

        if (attacker instanceof LivingEntity) {
            ((LivingEntity) attacker).damage(incomingDamage, parryPlayer);
        }

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
        return true;
    }

    /**
     * Reflect a thrown potion back at the thrower during Echoing Aegis.
     */
    public boolean handleParryPotion(Player parryPlayer, ThrownPotion potion) {
        if (!parryingPlayers.contains(parryPlayer.getUniqueId())) return false;
        Entity thrower = (Entity) potion.getShooter();
        if (thrower == null) return false;

        Vector reflect = thrower.getLocation().subtract(potion.getLocation()).toVector().normalize().multiply(1.2);
        potion.setVelocity(reflect);

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.2f);
        potion.getLocation().getWorld().spawnParticle(Particle.DUST, potion.getLocation(),
                15, 0.2, 0.2, 0.2, 0, d, true);

        endParry(parryPlayer, true);
        return false;
    }

    private void endParry(Player player, boolean success) {
        parryingPlayers.remove(player.getUniqueId());
        player.removeMetadata(META_PARRYING, plugin);

        if (success) {
            double healMin = plugin.getConfig().getDouble("abilities.auratus-echoing-aegis.heal-min", 4.0);
            double healMax = plugin.getConfig().getDouble("abilities.auratus-echoing-aegis.heal-max", 6.0);
            double heal    = healMin + Math.random() * (healMax - healMin);
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));

            int speedTicks   = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.speed-duration-ticks", 80);
            int weavingTicks = plugin.getConfig().getInt("abilities.auratus-echoing-aegis.weaving-duration-ticks", 60);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedTicks, 2, false, true));
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
    //  Hauling Strike — passive helpers (called from PassiveListener)
    // =========================================================================

    public void onHaulSneak(Player player, boolean sneaking) {
        if (sneaking) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, Integer.MAX_VALUE, 1, false, false));
            haulHaste.add(player.getUniqueId());
        } else {
            player.removePotionEffect(PotionEffectType.HASTE);
            haulHaste.remove(player.getUniqueId());
        }
    }

    public void applyHaulingStrike(Player attacker, LivingEntity target) {
        if (!attacker.isSneaking()) return;

        Vector forward = attacker.getEyeLocation().getDirection().normalize().multiply(1.4);
        forward.setY(0.3);
        target.setVelocity(forward);

        if (target instanceof Player targetPlayer) {
            ItemStack offhand = targetPlayer.getInventory().getItemInOffHand();
            if (offhand.getType() == Material.SHIELD && targetPlayer.getCooldown(Material.SHIELD) > 0) {
                int ext = plugin.getConfig().getInt("passives.auratus.hauling-strike.shield-cd-extension", 40);
                int cur = targetPlayer.getCooldown(Material.SHIELD);
                targetPlayer.setCooldown(Material.SHIELD, cur + ext);
                targetPlayer.sendMessage("§e§oYour shield cooldown was extended!");
            }
        }

        Particle.DustOptions d = new Particle.DustOptions(AURATUS_GOLD, 1.0f);
        target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, 1, 0),
                15, 0.3, 0.4, 0.3, 0, d, true);
    }

    public void tryAngelsGrasp(Player attacker, Location hitLoc) {
        String mode = plugin.getConfig().getString("passives.auratus.angels-grasp.mode", "pull");
        if (!"pull".equalsIgnoreCase(mode)) return;

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
        charges.remove(uuid);
        hookInFlight.remove(uuid);
        haulHaste.remove(uuid);
        BukkitTask t = chainTasks.remove(uuid);
        if (t != null) t.cancel();
        if (player.isOnline()) {
            player.removeMetadata(META_PARRYING, plugin);
        }
    }
}