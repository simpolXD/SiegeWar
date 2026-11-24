package com.abyssaldominion.siegewar;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;

/** Reset maps are isolated from survival inventories and external block automation. */
public final class ArenaGuards implements Listener {
    private final AbyssalDominionSiegeWarPlugin p;
    public ArenaGuards(AbyssalDominionSiegeWarPlugin plugin) { p = plugin; }
    private boolean controlled(Player player) {
        return p.game().session(player.getUniqueId()) != null || p.snapshots().pending(player.getUniqueId());
    }
    private boolean map(Location at) { return p.game().protectedArena(at) != null; }
    private boolean map(Inventory inventory) { return map(inventory.getLocation()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player) || e.getInventory().getHolder() instanceof Views.Menu) return;
        if (controlled(player) || (map(e.getInventory()) && !p.game().mayEdit(player, e.getInventory().getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transfer(InventoryMoveItemEvent e) {
        if (map(e.getSource()) || map(e.getDestination()) || map(e.getInitiator())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopperPickup(InventoryPickupItemEvent e) {
        if (map(e.getInventory()) || map(e.getItem().getLocation())
                || e.getItem().getItemStack().getItemMeta().getPersistentDataContainer()
                    .has(p.game().generationKey, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block != null && map(block.getLocation()) && !p.game().mayEdit(e.getPlayer(), block.getLocation())
                && !(e.getAction() == Action.LEFT_CLICK_BLOCK && p.game().mayBreak(e.getPlayer(), block.getLocation())))
            e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(PlayerInteractEntityEvent e) {
        if (controlled(e.getPlayer()) || (map(e.getRightClicked().getLocation())
                && !p.game().mayEdit(e.getPlayer(), e.getRightClicked().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void armorStand(PlayerArmorStandManipulateEvent e) {
        if (controlled(e.getPlayer()) || (map(e.getRightClicked().getLocation())
                && !p.game().mayEdit(e.getPlayer(), e.getRightClicked().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) { bucket(e); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent e) { bucket(e); }
    private void bucket(PlayerBucketEvent e) {
        Location at = e.getBlock().getLocation();
        if (controlled(e.getPlayer()) || (map(at) && !p.game().mayEdit(e.getPlayer(), at))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void vehicle(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player player && (controlled(player) || map(e.getVehicle().getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fish(PlayerFishEvent e) { if (controlled(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void leash(PlayerLeashEntityEvent e) {
        if (controlled(e.getPlayer()) || map(e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityPlace(EntityPlaceEvent e) {
        Player player = e.getPlayer();
        if ((player != null && controlled(player)) || (map(e.getEntity().getLocation())
                && (player == null || !p.game().mayEdit(player, e.getEntity().getLocation())))) e.setCancelled(true);
    }

    private boolean piston(Block piston, List<Block> blocks, org.bukkit.block.BlockFace direction) {
        if (map(piston.getLocation()) || map(piston.getRelative(direction).getLocation())) return true;
        return blocks.stream().anyMatch(b -> map(b.getLocation())
                || map(b.getRelative(direction).getLocation()) || map(b.getRelative(direction.getOppositeFace()).getLocation()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent e) {
        if (piston(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent e) {
        if (piston(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void dispense(BlockDispenseEvent e) {
        if (map(e.getBlock().getLocation()) || (e.getBlock().getBlockData() instanceof Directional d
                && map(e.getBlock().getRelative(d.getFacing()).getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fluid(BlockFromToEvent e) {
        if (map(e.getBlock().getLocation()) || map(e.getToBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) { if (map(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) { if (map(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void change(EntityChangeBlockEvent e) { if (map(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void itemSpawn(ItemSpawnEvent e) { if (map(e.getLocation())) e.setCancelled(true); }
    @EventHandler
    public void entityLoaded(EntityAddToWorldEvent e) {
        Entity entity = e.getEntity();
        if (!p.game().issued(entity)) return;
        // Spawn callbacks tag new entities later in the same tick; validate on the next tick.
        Bukkit.getScheduler().runTask(p, () -> {
            if (entity.isValid() && p.game().king(entity) == null && !p.game().currentIssued(entity)) entity.remove();
        });
    }
}
