package com.zyvenspawner;

import java.util.*;
import java.util.List;
import org.bukkit.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SpawnerListener implements Listener {

    private final ZyvenSpawnerPlugin pl;

    public SpawnerListener(ZyvenSpawnerPlugin pl) { this.pl = pl; }

    private boolean toolOk(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE) return true;
        FileConfiguration c = pl.getConfig();
        ItemStack hand = p.getInventory().getItemInMainHand();
        List<String> tools = c.getStringList("settings.required-tools");
        if (!tools.isEmpty() && !tools.contains(hand.getType().name())) return false;
        if (!c.getBoolean("settings.silk-touch-required", true)) return true;
        return hand.containsEnchantment(Enchantment.SILK_TOUCH);
    }

    private boolean allowed(Player p, SpawnerData d) {
        if (!pl.getConfig().getBoolean("settings.owner-only", false)) return true;
        return d.owner == null || d.owner.equals(p.getUniqueId()) || p.hasPermission("zyvenspawner.admin");
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        EntityType t = pl.itemType(e.getItemInHand());
        if (t == null) return;
        if (pl.cfg(t) == null) {
            e.setCancelled(true);
            return;
        }
        Block b = e.getBlockPlaced();
        SpawnerData d = new SpawnerData();
        d.type = t;
        d.owner = e.getPlayer().getUniqueId();
        pl.spawners().put(pl.key(b), d);
        pl.prep(b, t);
        e.getPlayer().sendMessage("§aPlaced a §e" + ZyvenSpawnerPlugin.pretty(t) + " Spawner§a. Right-click it to open.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        FileConfiguration c = pl.getConfig();
        SpawnerData d = pl.spawners().get(pl.key(b));
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);

        if (d != null) {
            if (!allowed(p, d)) {
                e.setCancelled(true);
                p.sendMessage("§cThis spawner belongs to someone else.");
                return;
            }
            if (!toolOk(p)) {
                e.setCancelled(true);
                p.sendMessage("§c[!] You need a Silk Touch pickaxe to break this spawner.");
                return;
            }
            e.setDropItems(false);
            e.setExpToDrop(0);
            pl.spawners().remove(pl.key(b));
            pl.giveSpawners(loc, d.type, d.stack);
            if (c.getBoolean("settings.lose-stored-loot-on-break", true)) {
                if (d.total() > 0 || d.xp > 0) {
                    p.sendMessage("§cThe stored loot and XP were lost. Empty the spawner before breaking it!");
                }
            } else {
                pl.giveLoot(p, d, loc);
            }
            return;
        }

        // natural / vanilla spawner
        if (!c.getBoolean("settings.convert-natural-spawners", true)) return;
        if (!toolOk(p)) return;
        if (!(b.getState() instanceof CreatureSpawner cs)) return;
        EntityType t = cs.getSpawnedType();
        if (t == null || pl.cfg(t) == null) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        b.getWorld().dropItemNaturally(loc, pl.createItem(t, 1));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        SpawnerData d = pl.spawners().get(pl.key(b));
        if (d == null) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        if (!allowed(p, d)) {
            e.setCancelled(true);
            p.sendMessage("§cThis spawner belongs to someone else.");
            return;
        }

        // right-click with the same spawner type to stack
        if (!p.isSneaking() && pl.itemType(hand) == d.type) {
            e.setCancelled(true);
            long max = pl.getConfig().getLong("settings.max-stack", 10000);
            long add = Math.min(hand.getAmount(), max - d.stack);
            if (add <= 0) {
                p.sendMessage("§cThis spawner stack is full (" + max + ").");
                return;
            }
            d.stack += add;
            hand.setAmount((int) (hand.getAmount() - add));
            p.sendMessage("§aStacked! Now §e" + d.stack + "x " + ZyvenSpawnerPlugin.pretty(d.type) + " Spawner§a.");
            return;
        }
        if (p.isSneaking() && hand.getType() != Material.AIR) return; // let players place blocks
        e.setCancelled(true);
        pl.gui().open(p, pl.key(b));
    }

    // ---------- explosion protection ----------
    private void protect(List<Block> blocks) {
        if (!pl.getConfig().getBoolean("settings.protect-from-explosions", true)) return;
        blocks.removeIf(b -> b.getType() == Material.SPAWNER && pl.spawners().containsKey(pl.key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { protect(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { protect(e.blockList()); }
}
