package com.zyvenspawner;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

/**
 * ZyvenSpawner - income spawners inspired by DonutSMP.
 * Credit: DonutSMP (this plugin is an unofficial fan recreation, not affiliated with DonutSMP).
 */
public class ZyvenSpawnerPlugin extends JavaPlugin implements Listener, TabExecutor {

    public static final String CREDIT = "§6Inspired by §eDonutSMP";

    static class SpawnerData {
        EntityType type;
        long stack = 1, xp = 0, progress = 0;
        Map<Material, Long> items = new LinkedHashMap<>();
        long total() { long t = 0; for (long v : items.values()) t += v; return t; }
    }

    static class GuiHolder implements InventoryHolder {
        final String key; final List<Material> shown = new ArrayList<>();
        GuiHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<String, SpawnerData> spawners = new HashMap<>();
    private NamespacedKey typeKey;
    private Economy econ;
    private File dataFile;
    private final Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "spawner_type");
        dataFile = new File(getDataFolder(), "data.yml");
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zyvenspawner").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
        getLogger().info("ZyvenSpawner enabled - inspired by DonutSMP (credit to DonutSMP).");
        if (econ == null) getLogger().warning("Vault economy not found: selling is disabled.");
    }

    @Override public void onDisable() { saveData(); }

    // ---------- helpers ----------
    private String key(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    private String pretty(EntityType t) {
        String[] p = t.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : p) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private ConfigurationSection cfg(EntityType t) { return getConfig().getConfigurationSection("spawners." + t.name()); }

    public ItemStack createItem(EntityType t, int amount) {
        ItemStack it = new ItemStack(Material.SPAWNER, amount);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§e" + pretty(t) + " Spawner");
        m.setLore(Arrays.asList("§7Place it to earn passive income.", "§7Right-click to open.", "", CREDIT));
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.name());
        it.setItemMeta(m);
        return it;
    }

    private EntityType itemType(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        try { return s == null ? null : EntityType.valueOf(s); } catch (Exception e) { return null; }
    }

    private void prep(Block b, EntityType t) {
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(t);
            cs.setRequiredPlayerRange(0);   // stop vanilla mob spawning
            cs.setMaxNearbyEntities(0);
            cs.setSpawnCount(0);
            cs.update();
        }
    }

    private boolean silk(Player p) {
        return p.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH);
    }

    private void giveSpawners(Location loc, EntityType t, long count) {
        while (count > 0) {
            int n = (int) Math.min(64, count);
            loc.getWorld().dropItemNaturally(loc, createItem(t, n));
            count -= n;
        }
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
                if (rng.nextDouble() * 100 >= ds.getDouble("chance", 100)) continue;
                int min = ds.getInt("min", 1), max = Math.max(min, ds.getInt("max", 1));
                long amt = (long) (min + rng.nextInt(max - min + 1)) * d.stack;
                amt = Math.min(amt, cap - total);
                if (amt <= 0) continue;
                d.items.merge(mat, amt, Long::sum);
                total += amt;
            }
        }
    }

    // ---------- events ----------
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        EntityType t = itemType(e.getItemInHand());
        if (t == null) return;
        if (cfg(t) == null) { e.setCancelled(true); return; }
        Block b = e.getBlockPlaced();
        SpawnerData d = new SpawnerData();
        d.type = t;
        spawners.put(key(b), d);
        prep(b, t);
        e.getPlayer().sendMessage("§aPlaced a §e" + pretty(t) + " Spawner§a. Right-click it to open.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        SpawnerData d = spawners.get(key(b));
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);

        if (d != null) {
            if (!silk(p) && p.getGameMode() != GameMode.CREATIVE) {
                e.setCancelled(true);
                p.sendMessage("§cYou need a Silk Touch pickaxe to break this spawner.");
                return;
            }
            e.setDropItems(false); e.setExpToDrop(0);
            spawners.remove(key(b));
            giveSpawners(loc, d.type, d.stack);
            for (Map.Entry<Material, Long> en : d.items.entrySet()) {
                long left = en.getValue();
                while (left > 0) {
                    int n = (int) Math.min(en.getKey().getMaxStackSize(), left);
                    Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(en.getKey(), n));
                    over.values().forEach(i -> p.getWorld().dropItemNaturally(loc, i));
                    left -= n;
                }
            }
            if (d.xp > 0) p.giveExp((int) Math.min(d.xp, Integer.MAX_VALUE));
            return;
        }

        // natural / vanilla spawner
        if (!getConfig().getBoolean("settings.require-silk-touch-natural", true)) return;
        if (!silk(p) || !(b.getState() instanceof CreatureSpawner cs)) return;
        EntityType t = cs.getSpawnedType();
        if (t == null || cfg(t) == null) return;
        e.setDropItems(false); e.setExpToDrop(0);
        b.getWorld().dropItemNaturally(loc, createItem(t, 1));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        SpawnerData d = spawners.get(key(b));
        if (d == null) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        if (!p.isSneaking() && itemType(hand) == d.type) {          // stack
            e.setCancelled(true);
            long max = getConfig().getLong("settings.max-stack", 10000);
            long add = Math.min(hand.getAmount(), max - d.stack);
            if (add <= 0) { p.sendMessage("§cThis spawner stack is full (" + max + ")."); return; }
            d.stack += add;
            hand.setAmount((int) (hand.getAmount() - add));
            p.sendMessage("§aStacked! Now §e" + d.stack + "x " + pretty(d.type) + " Spawner§a.");
            return;
        }
        if (p.isSneaking() && hand.getType() != Material.AIR) return; // allow placing blocks
        e.setCancelled(true);
        open(p, key(b));
    }

    // ---------- GUI ----------
    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.setDisplayName(name);
        im.setLore(Arrays.asList(lore));
        i.setItemMeta(im);
        return i;
    }

    private double price(EntityType t, Material m) {
        ConfigurationSection c = cfg(t);
        return c == null ? 0 : c.getDouble("drops." + m.name() + ".price", 0);
    }

    private void open(Player p, String k) {
        SpawnerData d = spawners.get(k);
        if (d == null) return;
        GuiHolder h = new GuiHolder(k);
        Inventory inv = Bukkit.createInventory(h, 54, "§8" + pretty(d.type) + " Spawner x" + d.stack);
        double worth = 0; int slot = 0;
        for (Map.Entry<Material, Long> en : d.items.entrySet()) {
            worth += en.getValue() * price(d.type, en.getKey());
            if (en.getValue() <= 0 || slot >= 45) continue;
            h.shown.add(en.getKey());
            ItemStack it = icon(en.getKey(), "§f" + en.getKey().name().toLowerCase().replace('_', ' '),
                    "§7Stored: §e" + en.getValue(),
                    "§7Value: §a$" + String.format("%,.2f", en.getValue() * price(d.type, en.getKey())), "",
                    "§eClick: §7take 64", "§eShift-click: §7take as many as fit");
            inv.setItem(slot++, it);
        }
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        ConfigurationSection c = cfg(d.type);
        inv.setItem(45, icon(Material.SPAWNER, "§e" + pretty(d.type) + " Spawner",
                "§7Stack: §e" + d.stack, "§7Interval: §e" + (c == null ? "?" : c.getLong("interval-seconds")) + "s",
                "§7Stored items: §e" + d.total()));
        inv.setItem(48, icon(Material.EXPERIENCE_BOTTLE, "§aCollect XP", "§7Stored XP: §e" + d.xp, "", "§eClick to collect"));
        inv.setItem(49, icon(Material.GOLD_INGOT, "§6Sell All", "§7Worth: §a$" + String.format("%,.2f", worth), "",
                econ == null ? "§cVault not installed" : "§eClick to sell everything"));
        inv.setItem(53, icon(Material.PAPER, CREDIT, "§7Unofficial fan recreation of", "§7the DonutSMP spawner system."));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        SpawnerData d = spawners.get(h.key);
        if (d == null) { p.closeInventory(); return; }
        int s = e.getRawSlot();

        if (s >= 0 && s < h.shown.size()) {
            Material m = h.shown.get(s);
            long have = d.items.getOrDefault(m, 0L);
            long want = Math.min(have, e.isShiftClick() ? 36L * m.getMaxStackSize() : 64);
            long given = 0;
            while (given < want) {
                int n = (int) Math.min(m.getMaxStackSize(), want - given);
                Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, n));
                int notGiven = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                given += n - notGiven;
                if (notGiven > 0) break;
            }
            d.items.merge(m, -given, Long::sum);
            if (d.items.get(m) <= 0) d.items.remove(m);
        } else if (s == 48) {
            if (d.xp > 0) { p.giveExp((int) Math.min(d.xp, Integer.MAX_VALUE)); d.xp = 0; }
        } else if (s == 49) {
            if (econ == null) { p.sendMessage("§cSelling is disabled (Vault missing)."); return; }
            double total = 0;
            for (Map.Entry<Material, Long> en : d.items.entrySet()) total += en.getValue() * price(d.type, en.getKey());
            d.items.clear();
            if (total > 0) { econ.depositPlayer(p, total); p.sendMessage("§aSold for §6$" + String.format("%,.2f", total)); }
        } else return;
        open(p, h.key);
    }

    // ---------- command ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("credits")) {
            s.sendMessage("§6ZyvenSpawner §7- " + CREDIT);
            s.sendMessage("§7Spawner system concept credit: §eDonutSMP§7. Unofficial, not affiliated.");
            return true;
        }
        if (!s.hasPermission("zyvenspawner.admin")) { s.sendMessage("§cNo permission."); return true; }
        switch (a[0].toLowerCase()) {
            case "reload" -> { reloadConfig(); s.sendMessage("§aReloaded."); }
            case "list" -> {
                ConfigurationSection sec = getConfig().getConfigurationSection("spawners");
                s.sendMessage("§eSpawners: §f" + (sec == null ? "none" : String.join(", ", sec.getKeys(false))));
            }
            case "give" -> {
                if (a.length < 3) { s.sendMessage("§cUsage: /zs give <player> <type> [amount]"); return true; }
                Player t = Bukkit.getPlayer(a[1]);
                if (t == null) { s.sendMessage("§cPlayer not found."); return true; }
                EntityType et;
                try { et = EntityType.valueOf(a[2].toUpperCase()); } catch (Exception ex) { s.sendMessage("§cUnknown type."); return true; }
                if (cfg(et) == null) { s.sendMessage("§cThat type isn't in config.yml."); return true; }
                int n = a.length > 3 ? Math.max(1, Math.min(64, Integer.parseInt(a[3]))) : 1;
                t.getInventory().addItem(createItem(et, n)).values().forEach(i -> t.getWorld().dropItemNaturally(t.getLocation(), i));
                s.sendMessage("§aGave " + n + "x " + pretty(et) + " Spawner to " + t.getName());
            }
            default -> s.sendMessage("§cUsage: /zs <give|list|reload|credits>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> r = new ArrayList<>();
        if (a.length == 1) r.addAll(List.of("give", "list", "reload", "credits"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) Bukkit.getOnlinePlayers().forEach(p -> r.add(p.getName()));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
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
            for (Map.Entry<Material, Long> it : d.items.entrySet()) y.set(k + ".items." + it.getKey().name(), it.getValue());
        }
        try { y.save(dataFile); } catch (Exception ex) { getLogger().severe("Could not save data: " + ex.getMessage()); }
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
                ConfigurationSection it = y.getConfigurationSection(k + ".items");
                if (it != null) for (String m : it.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    if (mat != null) d.items.put(mat, it.getLong(m));
                }
                spawners.put(y.getString(k + ".loc"), d);
            } catch (Exception ex) { getLogger().warning("Skipped bad entry " + k); }
        }
    }
}
