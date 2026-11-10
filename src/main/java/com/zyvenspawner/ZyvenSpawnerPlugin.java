package com.zyvenspawner;

import java.io.File;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ZyvenSpawner - income spawners inspired by DonutSMP.
 * Credit: DonutSMP (unofficial fan recreation, not affiliated with DonutSMP).
 */
public class ZyvenSpawnerPlugin extends JavaPlugin implements TabExecutor {

    public static final String CREDIT = "§6Inspired by §eDonutSMP";

    private final Map<String, SpawnerData> spawners = new HashMap<>();
    private final Random rng = new Random();
    private NamespacedKey typeKey;
    private VaultHook vault;
    private File dataFile;
    private SpawnerGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "spawner_type");
        dataFile = new File(getDataFolder(), "data.yml");

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            VaultHook h = new VaultHook();
            if (h.setup(getServer())) vault = h;
        }
        if (vault == null) getLogger().warning("Vault economy not found: selling is disabled.");

        loadData();
        gui = new SpawnerGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);
        getCommand("zyvenspawner").setExecutor(this);

        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, gui::refreshAll, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
        getLogger().info("ZyvenSpawner enabled - inspired by DonutSMP (credit to DonutSMP).");
    }

    @Override
    public void onDisable() { saveData(); }

    // ---------- accessors / helpers ----------
    public Map<String, SpawnerData> spawners() { return spawners; }
    public SpawnerGui gui() { return gui; }
    public boolean hasEconomy() { return vault != null; }
    public void deposit(Player p, double amount) { if (vault != null) vault.deposit(p, amount); }

    public String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public static String nice(String name) {
        StringBuilder sb = new StringBuilder();
        for (String p : name.toLowerCase().split("_")) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    public static String pretty(EntityType t) { return nice(t.name()); }

    public ConfigurationSection cfg(EntityType t) {
        return getConfig().getConfigurationSection("spawners." + t.name());
    }

    public double price(EntityType t, Material m) {
        ConfigurationSection c = cfg(t);
        return c == null ? 0 : c.getDouble("drops." + m.name() + ".price", 0);
    }

    public ItemStack createItem(EntityType t, int amount) {
        ItemStack it = new ItemStack(Material.SPAWNER, amount);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§e" + pretty(t) + " Spawner");
        m.setLore(Arrays.asList("§7Place it to earn passive income.", "§7Right-click it to open.", "", CREDIT));
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.name());
        it.setItemMeta(m);
        return it;
    }

    public EntityType itemType(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        try { return s == null ? null : EntityType.valueOf(s); } catch (Exception e) { return null; }
    }

    /** Turns the block into a silent spawner: shows the mob, never spawns one. */
    public void prep(Block b, EntityType t) {
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(t);
            cs.setRequiredPlayerRange(0);
            cs.setMaxNearbyEntities(0);
            cs.setSpawnCount(0);
            cs.update();
        }
    }

    public void giveSpawners(Location loc, EntityType t, long count) {
        while (count > 0) {
            int n = (int) Math.min(64, count);
            loc.getWorld().dropItemNaturally(loc, createItem(t, n));
            count -= n;
        }
    }

    /** Moves stored loot and XP to the player (overflow is dropped on the ground). */
    public void giveLoot(Player p, SpawnerData d, Location loc) {
        for (Map.Entry<Material, Long> en : d.items.entrySet()) {
            long left = en.getValue();
            while (left > 0) {
                int n = (int) Math.min(en.getKey().getMaxStackSize(), left);
                p.getInventory().addItem(new ItemStack(en.getKey(), n))
                        .values().forEach(i -> p.getWorld().dropItemNaturally(loc, i));
                left -= n;
            }
        }
        if (d.xp > 0) p.giveExp((int) Math.min(d.xp, Integer.MAX_VALUE));
    }

    // ---------- income ----------
    private void tick() {
        boolean loadedOnly = getConfig().getBoolean("settings.only-generate-in-loaded-chunks", true);
        long cap = getConfig().getLong("settings.max-storage", 1000000);
        for (Map.Entry<String, SpawnerData> e : spawners.entrySet()) {
            SpawnerData d = e.getValue();
            ConfigurationSection c = cfg(d.type);
            if (c == null) continue;
            String[] p = e.getKey().split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            if (loadedOnly && !w.isChunkLoaded(Integer.parseInt(p[1]) >> 4, Integer.parseInt(p[3]) >> 4)) continue;
            if (++d.progress < c.getLong("interval-seconds", 15)) continue;
            d.progress = 0;

            long total = d.total();
            d.xp += c.getLong("xp-per-roll", 0) * d.stack;
            ConfigurationSection drops = c.getConfigurationSection("drops");
            if (drops == null) continue;
            for (String m : drops.getKeys(false)) {
                Material mat = Material.matchMaterial(m);
                if (mat == null || total >= cap) continue;
                ConfigurationSection ds = drops.getConfigurationSection(m);
                if (ds == null) continue;
                if (rng.nextDouble() * 100 >= ds.getDouble("chance", 100)) continue;
                int min = ds.getInt("min", 1);
                int max = Math.max(min, ds.getInt("max", 1));
                long amt = (long) (min + rng.nextInt(max - min + 1)) * d.stack;
                amt = Math.min(amt, cap - total);
                if (amt <= 0) continue;
                d.items.merge(mat, amt, Long::sum);
                total += amt;
            }
        }
    }

    // ---------- command ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("credits")) {
            s.sendMessage("§6ZyvenSpawner §7- " + CREDIT);
            s.sendMessage("§7Spawner system concept credit: §eDonutSMP§7. Unofficial, not affiliated.");
            return true;
        }
        if (!s.hasPermission("zyvenspawner.admin")) {
            s.sendMessage("§cNo permission.");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                s.sendMessage("§aReloaded.");
            }
            case "list" -> {
                ConfigurationSection sec = getConfig().getConfigurationSection("spawners");
                s.sendMessage("§eSpawners: §f" + (sec == null ? "none" : String.join(", ", sec.getKeys(false))));
            }
            case "give" -> {
                if (a.length < 3) {
                    s.sendMessage("§cUsage: /zs give <player> <type> [amount]");
                    return true;
                }
                Player t = Bukkit.getPlayer(a[1]);
                if (t == null) {
                    s.sendMessage("§cPlayer not found.");
                    return true;
                }
                EntityType et;
                try {
                    et = EntityType.valueOf(a[2].toUpperCase());
                } catch (Exception ex) {
                    s.sendMessage("§cUnknown type.");
                    return true;
                }
                if (cfg(et) == null) {
                    s.sendMessage("§cThat type isn't in config.yml.");
                    return true;
                }
                int n = 1;
                if (a.length > 3) {
                    try { n = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
                }
                t.getInventory().addItem(createItem(et, n))
                        .values().forEach(i -> t.getWorld().dropItemNaturally(t.getLocation(), i));
                s.sendMessage("§aGave " + n + "x " + pretty(et) + " Spawner to " + t.getName());
            }
            default -> s.sendMessage("§cUsage: /zs <give|list|reload|credits>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> r = new ArrayList<>();
        if (a.length == 1) {
            r.addAll(List.of("give", "list", "reload", "credits"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> r.add(p.getName()));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            ConfigurationSection sec = getConfig().getConfigurationSection("spawners");
            if (sec != null) r.addAll(sec.getKeys(false));
        }
        r.removeIf(x -> !x.toLowerCase().startsWith(a[a.length - 1].toLowerCase()));
        return r;
    }

    // ---------- persistence ----------
    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, SpawnerData> e : spawners.entrySet()) {
            String k = e.getKey().replace('.', '_');
            SpawnerData d = e.getValue();
            y.set(k + ".loc", e.getKey());
            y.set(k + ".type", d.type.name());
            y.set(k + ".stack", d.stack);
            y.set(k + ".xp", d.xp);
            if (d.owner != null) y.set(k + ".owner", d.owner.toString());
            for (Map.Entry<Material, Long> it : d.items.entrySet()) {
                y.set(k + ".items." + it.getKey().name(), it.getValue());
            }
        }
        try {
            y.save(dataFile);
        } catch (Exception ex) {
            getLogger().severe("Could not save data: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : y.getKeys(false)) {
            try {
                SpawnerData d = new SpawnerData();
                d.type = EntityType.valueOf(y.getString(k + ".type"));
                d.stack = y.getLong(k + ".stack", 1);
                d.xp = y.getLong(k + ".xp", 0);
                String owner = y.getString(k + ".owner");
                if (owner != null) d.owner = UUID.fromString(owner);
                ConfigurationSection it = y.getConfigurationSection(k + ".items");
                if (it != null) {
                    for (String m : it.getKeys(false)) {
                        Material mat = Material.matchMaterial(m);
                        if (mat != null) d.items.put(mat, it.getLong(m));
                    }
                }
                spawners.put(y.getString(k + ".loc"), d);
            } catch (Exception ex) {
                getLogger().warning("Skipped bad entry " + k);
            }
        }
    }
}

class SpawnerData {
    public EntityType type;
    public long stack = 1;
    public long xp = 0;
    public long progress = 0;
    public UUID owner;
    public Map<Material, Long> items = new LinkedHashMap<>();

    public long total() {
        long t = 0;
        for (long v : items.values()) t += v;
        return t;
    }
}

/** Kept separate so the plugin still loads when Vault is not installed. */
final class VaultHook {
    private Economy econ;

    boolean setup(Server server) {
        RegisteredServiceProvider<Economy> rsp = server.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    void deposit(OfflinePlayer p, double amount) {
        econ.depositPlayer(p, amount);
    }
}

/**
 * Spawner menu: items in slots 0-44, bottom row = Sell All (48), spawner info (49), Take All (50),
 * page arrows in 45 / 53.
 */
class SpawnerGui implements Listener {

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

 
