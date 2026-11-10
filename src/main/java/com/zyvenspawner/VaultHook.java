package com.zyvenspawner;

import java.util.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.*;
import org.bukkit.plugin.RegisteredServiceProvider;

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
