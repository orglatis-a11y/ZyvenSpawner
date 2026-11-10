package com.zyvenspawner;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;

public class SpawnerData {
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
