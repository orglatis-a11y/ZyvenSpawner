package com.zyvenspawner;

import java.util.*;
import java.util.List;
import java.util.Map;
import org.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SpawnerGui implements Listener {

    private static final int PER_PAGE = 45;

    static class Holder implements InventoryHolder {
        final String key;
        final List<Material> shown = new ArrayList<>();
        int page = 0;

        Holder(String key) { this.key = key; }

        @Override
        public Inventory getInventory() { return null; }
    }

    private final ZyvenSpawnerPlugin pl;

    public SpawnerGui(ZyvenSpawnerPlugin pl) { this.pl = pl; }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.setDisplayName(name);
        im.setLore(Arrays.asList(lore));
        i.setItemMeta(im);
        return i;
    }

    private String compact(double n) {
        if (n >= 1_000_000_000) return String.format("%.2fB", n / 1_000_000_000);
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000);
        if (n >= 10_000) return String.format("%.1fK", n / 1_000);
        return n == Math.floor(n) ? String.format("%,d", (long) n) : String.format("%,.2f", n);
    }

    private Material eggFor(EntityType t) {
        Material m = Material.matchMaterial(t.name() + "_SPAWN_EGG");
        return m == null ? Material.SPAWNER : m;
    }

    private void fill(Inventory inv, Holder h, SpawnerData d) {
        inv.clear();
        h.shown.clear();

        List<Map.Entry<Material, Long>> list = new ArrayList<>();
        double worth = 0;
        for (Map.Entry<Material, Long> en : d.items.entrySet()) {
            if (en.getValue() <= 0) continue;
            list.add(en);
            worth += en.getValue() * pl.price(d.type, en.getKey());
        }
        list.sort((x, y) -> Double.compare(
                y.getValue() * pl.price(d.type, y.getKey()),
                x.getValue() * pl.price(d.type, x.getKey())));

        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        if (h.page >= pages) h.page = pages - 1;
        if (h.page < 0) h.page = 0;

        int start = h.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < list.size(); i++) {
            Material m = list.get(start + i).getKey();
            long amt = list.get(start + i).getValue();
            h.shown.add(m);
            ItemStack it = icon(m, "§f" + ZyvenSpawnerPlugin.nice(m.name()),
                    "§a$ §f" + compact(amt * pl.price(d.type, m)), "",
                    "§7Stored: §e" + String.format("%,d", amt),
                    "§eClick §7- take 1 stack",
                    "§eShift-click §7- take all");
            it.setAmount((int) Math.max(1, Math.min(m.getMaxStackSize(), amt)));
            inv.setItem(i, it);
        }

        ConfigurationSection c = pl.cfg(d.type);
        inv.setItem(48, icon(Material.GOLD_INGOT, "§6Sell All",
                "§a$ §f" + compact(worth), "",
                pl.hasEconomy() ? "§eClick to sell everything" : "§cVault not installed"));
        inv.setItem(49, icon(eggFor(d.type), "§e" + ZyvenSpawnerPlugin.pretty(d.type) + " Spawners",
                "§7Stack: §e" + String.format("%,d", d.stack),
                "§7Income: §eevery " + (c == null ? "?" : c.getLong("interval-seconds")) + "s",
                "§7Stored items: §e" + String.format("%,d", d.total()),
                "§7Stored XP: §e" + String.format("%,d", d.xp), "",
                "§eClick §7to collect XP", "",
                ZyvenSpawnerPlugin.CREDIT));
        inv.setItem(50, icon(Material.DROPPER, "§bTake All",
                "§7Move all items to your inventory", "", "§eClick to take"));
        if (h.page > 0) {
            inv.setItem(45, icon(Material.ARROW, "§ePrevious Page", "§7Page " + h.page + "/" + pages));
        }
        if (h.page < pages - 1) {
            inv.setItem(53, icon(Material.ARROW, "§eNext Page", "§7Page " + (h.page + 2) + "/" + pages));
        }
    }

    public void open(Player p, String key) {
        SpawnerData d = pl.spawners().get(key);
        if (d == null) return;
        Holder h = new Holder(key);
        Inventory inv = Bukkit.createInventory(h, 54, "§8" + ZyvenSpawnerPlugin.pretty(d.type) + " Spawners");
        fill(inv, h, d);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f);
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Holder h) {
                SpawnerData d = pl.spawners().get(h.key);
                if (d == null) p.closeInventory();
                else fill(top, h, d);
            }
        }
    }

    private long give(Player p, SpawnerData d, Material m, long want) {
        long have = d.items.getOrDefault(m, 0L);
        want = Math.min(want, have);
        long given = 0;
        while (given < want) {
            int n = (int) Math.min(m.getMaxStackSize(), want - given);
            int notGiven = p.getInventory().addItem(new ItemStack(m, n))
                    .values().stream().mapToInt(ItemStack::getAmount).sum();
            given += n - notGiven;
            if (notGiven > 0) break;
        }
        if (given > 0) {
            long left = have - given;
            if (left <= 0) d.items.remove(m);
            else d.items.put(m, left);
        }
        return given;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        SpawnerData d = pl.spawners().get(h.key);
        if (d == null) {
            p.closeInventory();
            return;
        }
        int s = e.getRawSlot();
        Sound sound = Sound.UI_BUTTON_CLICK;

        if (s >= 0 && s < h.shown.size()) {
            Material m = h.shown.get(s);
            give(p, d, m, e.isShiftClick() ? Long.MAX_VALUE : m.getMaxStackSize());
            sound = Sound.ENTITY_ITEM_PICKUP;
        } else if (s == 45) {
            h.page--;
        } else if (s == 53) {
            h.page++;
        } else if (s == 48) {
            if (!pl.hasEconomy()) {
                p.sendMessage("§cSelling is disabled (Vault missing).");
                return;
            }
            double total = 0;
            for (Map.Entry<Material, Long> en : d.items.entrySet()) {
                total += en.getValue() * pl.price(d.type, en.getKey());
            }
            d.items.clear();
            if (total > 0) {
                pl.deposit(p, total);
                p.sendMessage("§aSold for §6$" + String.format("%,.2f", total));
                sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            }
        } else if (s == 49) {
            if (d.xp > 0) {
                p.giveExp((int) Math.min(d.xp, Integer.MAX_VALUE));
                d.xp = 0;
                sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            }
        } else if (s == 50) {
            for (Material m : new ArrayList<>(d.items.keySet())) {
                long want = d.items.get(m);
                if (give(p, d, m, want) < want) break; // inventory full
            }
            sound = Sound.ENTITY_ITEM_PICKUP;
        } else {
            return;
        }

        p.playSound(p.getLocation(), sound, 0.5f, 1f);
        fill(e.getView().getTopInventory(), h, d);
    }
}
