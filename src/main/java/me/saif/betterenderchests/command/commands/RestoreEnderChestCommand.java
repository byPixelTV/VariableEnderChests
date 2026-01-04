package me.saif.betterenderchests.command.commands;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.NBTCompound;
import de.tr7zw.changeme.nbtapi.NBTItem;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.command.PluginCommand;
import me.saif.betterenderchests.enderchest.EnderChest;
import me.saif.betterenderchests.enderchest.EnderChestManager;
import me.saif.betterenderchests.enderchest.EnderChestSnapshot;
import me.saif.betterenderchests.data.database.SQLiteDatabase;
import me.saif.betterenderchests.data.SQLDataManager;
import me.saif.betterenderchests.data.SQLiteDataManager;
import me.saif.betterenderchests.lang.MessageKey;
import me.saif.betterenderchests.lang.Messenger;
import me.saif.betterenderchests.lang.placeholder.Placeholder;
import me.saif.betterenderchests.lang.placeholder.PlaceholderResult;
import me.saif.betterenderchests.utils.Callback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class RestoreEnderChestCommand extends PluginCommand {

    private final UUID consoleUUID = new UUID(0, 0);
    private final Map<UUID, EnderChest> toClear = new HashMap<>();
    private final Placeholder<EnderChest> enderChestPlaceholder = Placeholder.getPlaceholder("player", EnderChest::getName);
    private final Placeholder<String> usagePlaceholder = Placeholder.getStringPlaceholder("command");
    private final EnderChestManager ecm;
    private final Messenger messenger;
    private VariableEnderChests plugin;

    private final String PERMISSION = "enderchest.restore";

    public RestoreEnderChestCommand(VariableEnderChests plugin) {
        super("restoreenderchest", "restoreechest");
        this.plugin = plugin;
        this.ecm = plugin.getEnderChestManager();
        this.messenger = plugin.getMessenger();
    }

    @Override
    public void onCommand(CommandSender sender, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messenger.sendMessage(sender, MessageKey.EC_COMMAND_NO_PERMISSION_SELF);
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be executed by a player.");
            return;
        }

        if (args.length < 2) {
            PlaceholderResult result = usagePlaceholder.getResult("/" + alias + " <backup-file> <player>");
            messenger.sendMessage(sender, MessageKey.COMMAND_USAGE, result);
            return;
        }

        restoreEnderChest((Player) sender, args[0], args[1]);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION))
            return new ArrayList<>();

        if (args.length == 1) {
            // Return list of backup files
            File backupsFolder = new File(plugin.getDataFolder(), "backups");
            if (!backupsFolder.exists() || !backupsFolder.isDirectory()) {
                return new ArrayList<>();
            }

            File[] backupFiles = backupsFolder.listFiles((dir, name) -> name.endsWith(".db"));
            if (backupFiles == null || backupFiles.length == 0) {
                return new ArrayList<>();
            }

            List<String> backupNames = Arrays.stream(backupFiles)
                    .map(File::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
            return backupNames;
        } else if (args.length == 2) {
            // Return list of online players
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }

    public void restoreEnderChest(Player sender, String backupFileName, String targetPlayerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File backupsFolder = new File(plugin.getDataFolder(), "backups");
            File backupFile = new File(backupsFolder, backupFileName);

            if (!backupFile.exists() || !backupFile.isFile()) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    sender.sendMessage("§cBackup file '" + backupFileName + "' not found!")
                );
                return;
            }

            try {
                plugin.getLogger().info("[Restore] Opening backup file: " + backupFileName);
                
                // Create a temporary database connection to the backup file
                SQLiteDatabase backupDatabase = new SQLiteDatabase(backupsFolder, backupFileName);
                SQLiteDataManager backupDataManager = new SQLiteDataManager(backupDatabase);
                backupDataManager.init();

                plugin.getLogger().info("[Restore] Loading ender chest for: " + targetPlayerName);
                
                // Log raw data from backup database
                try {
                    java.sql.Connection conn = backupDatabase.getConnection();
                    java.sql.PreparedStatement stmt = conn.prepareStatement(
                        "SELECT enderchests.UUID, players.NAME, ROWS, CONTENTS FROM enderchests " +
                        "LEFT JOIN players ON enderchests.UUID = players.UUID " +
                        "WHERE LOWER(players.NAME) = LOWER(?)"
                    );
                    stmt.setString(1, targetPlayerName);
                    java.sql.ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        String rawContents = rs.getString("CONTENTS");
                        plugin.getLogger().info("[Restore] RAW BACKUP DATA - UUID: " + rs.getString("UUID"));
                        plugin.getLogger().info("[Restore] RAW BACKUP DATA - NAME: " + rs.getString("NAME"));
                        plugin.getLogger().info("[Restore] RAW BACKUP DATA - ROWS: " + rs.getInt("ROWS"));
                        plugin.getLogger().info("[Restore] RAW BACKUP DATA - CONTENTS: " + rawContents);
                        plugin.getLogger().info("[Restore] RAW BACKUP DATA - CONTENTS length: " + (rawContents != null ? rawContents.length() : "null"));

                        // Decode the base64 to see actual content
                        if (rawContents != null && rawContents.startsWith("nbtbytes:")) {
                            String base64Data = rawContents.substring(9);
                            byte[] decoded = me.saif.betterenderchests.utils.Base64Coder.decodeLines(base64Data);
                            plugin.getLogger().info("[Restore] Decoded byte array length: " + decoded.length + " bytes");
                            plugin.getLogger().info("[Restore] First 20 bytes (hex): " + bytesToHex(decoded, Math.min(20, decoded.length)));
                        }
                    } else {
                        plugin.getLogger().warning("[Restore] No data found in backup for player: " + targetPlayerName);
                    }
                    rs.close();
                    stmt.close();
                } catch (Exception e) {
                    plugin.getLogger().warning("[Restore] Error reading raw backup data: " + e.getMessage());
                    e.printStackTrace();
                }
                
                // Load the ender chest from the backup using loadEnderChestsByName directly
                Set<String> names = new HashSet<>();
                names.add(targetPlayerName);
                Map<String, EnderChestSnapshot> resultMap = backupDataManager.loadEnderChestsByName(names);
                
                plugin.getLogger().info("[Restore] loadEnderChestsByName returned: " + (resultMap != null ? ("map with " + resultMap.size() + " entries") : "null"));
                
                EnderChestSnapshot snapshot;
                if (resultMap != null && !resultMap.isEmpty()) {
                    snapshot = resultMap.get(targetPlayerName.toLowerCase());
                    plugin.getLogger().info("[Restore] Got snapshot from map for key '" + targetPlayerName.toLowerCase() + "': " + (snapshot != null ? "found" : "not found"));
                } else {
                    snapshot = null;
                }

                if (snapshot == null) {
                    plugin.getLogger().warning("[Restore] Snapshot is null for player: " + targetPlayerName);
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        sender.sendMessage("§cPlayer '" + targetPlayerName + "' not found in backup!")
                    );
                    backupDatabase.close();
                    return;
                }
                
                plugin.getLogger().info("[Restore] Snapshot loaded - UUID: " + snapshot.getUuid() + ", Name: " + snapshot.getName() + ", Rows: " + snapshot.getRows());
                ItemStack[] contents = snapshot.getContents();
                plugin.getLogger().info("[Restore] Contents array length: " + contents.length);
                
                // Debug: Try to manually deserialize the raw string to see what happens
                try {
                    java.sql.Connection conn = backupDatabase.getConnection();
                    java.sql.PreparedStatement stmt = conn.prepareStatement(
                        "SELECT CONTENTS FROM enderchests " +
                        "LEFT JOIN players ON enderchests.UUID = players.UUID " +
                        "WHERE LOWER(players.NAME) = LOWER(?)"
                    );
                    stmt.setString(1, targetPlayerName);
                    java.sql.ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        String rawContents = rs.getString("CONTENTS");
                        plugin.getLogger().info("[Restore] Manual deserialization test starting...");
                        plugin.getLogger().info("[Restore] Raw string: " + rawContents);
                        
                        // Try to deserialize manually
                        ItemStack[] manualContents = me.saif.betterenderchests.utils.ItemStackSerializer.deserialize(rawContents);
                        plugin.getLogger().info("[Restore] Manual deserialization result: array length = " + (manualContents != null ? manualContents.length : "null"));
                        
                        if (manualContents != null) {
                            int manualNonEmpty = 0;
                            for (int i = 0; i < manualContents.length; i++) {
                                if (manualContents[i] != null && manualContents[i].getType() != Material.AIR) {
                                    manualNonEmpty++;
                                    plugin.getLogger().info("[Restore] MANUAL Slot " + i + ": " + manualContents[i].getType() + " x" + manualContents[i].getAmount());
                                }
                            }
                            plugin.getLogger().info("[Restore] Manual deserialization found " + manualNonEmpty + " items");
                            
                            // Use the manually deserialized contents
                            contents = manualContents;
                        }
                    }
                    rs.close();
                    stmt.close();
                } catch (Exception e) {
                    plugin.getLogger().warning("[Restore] Error in manual deserialization: " + e.getMessage());
                    e.printStackTrace();
                }
                
                int nonEmptyCount = 0;
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && contents[i].getType() != Material.AIR) {
                        nonEmptyCount++;
                        plugin.getLogger().info("[Restore] BACKUP Slot " + i + ": " + contents[i].getType() + " x" + contents[i].getAmount());
                    }
                }
                plugin.getLogger().info("[Restore] Total non-empty slots in BACKUP: " + nonEmptyCount);
                
                // Close database after we've extracted all data
                backupDatabase.close();
                
                // Also load from current database for comparison
                plugin.getLogger().info("[Restore] ===== Comparing with CURRENT database =====");
                EnderChestSnapshot currentSnapshot = plugin.getDataManager().loadEnderChest(targetPlayerName);
                if (currentSnapshot != null) {
                    ItemStack[] currentContents = currentSnapshot.getContents();
                    int currentNonEmpty = 0;
                    for (int i = 0; i < currentContents.length; i++) {
                        if (currentContents[i] != null && currentContents[i].getType() != Material.AIR) {
                            currentNonEmpty++;
                            plugin.getLogger().info("[Restore] CURRENT Slot " + i + ": " + currentContents[i].getType() + " x" + currentContents[i].getAmount());
                        }
                    }
                    plugin.getLogger().info("[Restore] Total non-empty slots in CURRENT: " + currentNonEmpty);
                } else {
                    plugin.getLogger().info("[Restore] No current data found for comparison");
                }
                plugin.getLogger().info("[Restore] ===== End comparison =====");

                // Create shulker box(es) with the ender chest contents
                final ItemStack[] finalContents = contents;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().info("[Restore] Creating shulkers on main thread");
                    
                    int totalItems = 0;
                    for (ItemStack item : finalContents) {
                        if (item != null && item.getType() != Material.AIR) {
                            totalItems++;
                        }
                    }
                    
                    plugin.getLogger().info("[Restore] Total items to restore: " + totalItems);
                    
                    // Calculate how many shulkers we need (27 items per shulker)
                    int numShulkers = (int) Math.ceil(finalContents.length / 27.0);
                    plugin.getLogger().info("[Restore] Number of shulkers needed: " + numShulkers);
                    
                    List<ItemStack> shulkers = new ArrayList<>();
                    
                    for (int page = 0; page < numShulkers; page++) {
                        plugin.getLogger().info("[Restore] Creating shulker page " + (page + 1) + "/" + numShulkers);
                        ItemStack shulker = createShulkerWithContents(finalContents, snapshot.getRows(), targetPlayerName, page, numShulkers);
                        shulkers.add(shulker);
                    }
                    
                    // Count empty slots
                    int emptySlots = 0;
                    for (ItemStack item : sender.getInventory().getContents()) {
                        if (item == null || item.getType() == Material.AIR) {
                            emptySlots++;
                        }
                    }
                    
                    // Add shulkers to inventory or drop them
                    int added = 0;
                    int dropped = 0;
                    
                    for (ItemStack shulker : shulkers) {
                        if (emptySlots > 0) {
                            sender.getInventory().addItem(shulker);
                            added++;
                            emptySlots--;
                        } else {
                            sender.getWorld().dropItem(sender.getLocation(), shulker);
                            dropped++;
                        }
                    }
                    
                    if (dropped > 0 && added > 0) {
                        sender.sendMessage("§aRestored ender chest for §e" + targetPlayerName + "§a! " + added + " shulker(s) added to inventory, " + dropped + " dropped.");
                    } else if (dropped > 0) {
                        sender.sendMessage("§aRestored ender chest for §e" + targetPlayerName + "§a! " + dropped + " shulker(s) dropped at your location.");
                    } else {
                        sender.sendMessage("§aRestored ender chest for §e" + targetPlayerName + "§a! " + added + " shulker(s) added to your inventory.");
                    }
                });

            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§cError loading backup: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });
    }

    private ItemStack createShulkerWithContents(
            ItemStack[] contents,
            int rows,
            String playerName,
            int page,
            int totalPages
    ) {
        plugin.getLogger().info("[Restore] createShulkerWithContents - page: " + page + ", totalPages: " + totalPages);

        ItemStack shulkerBoxItem = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) shulkerBoxItem.getItemMeta();
        assert meta != null;
        ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();

        Inventory shulkerInv = Bukkit.createInventory(null, 27);

        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, contents.length);

        plugin.getLogger().info("[Restore] Copying items from index " + startIndex + " to " + endIndex);

        int itemsAdded = 0;
        for (int i = startIndex; i < endIndex; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                int targetSlot = i - startIndex;
                shulkerInv.setItem(targetSlot, item.clone());

                plugin.getLogger().info("[Restore] Adding to slot " + targetSlot + ": "
                        + item.getType() + " x" + item.getAmount());

                itemsAdded++;
            }
        }

        // Apply inventory to shulker
        shulkerBox.getInventory().setContents(shulkerInv.getContents());
        meta.setBlockState(shulkerBox);

        // Display name
        if (totalPages > 1) {
            meta.setDisplayName("§6" + playerName + "'s Ender Chest (Page "
                    + (page + 1) + "/" + totalPages + ")");
        } else {
            meta.setDisplayName("§6" + playerName + "'s Ender Chest");
        }

        // Lore
        List<String> lore = new ArrayList<>();
        lore.add("§7Rows: " + rows);
        lore.add("§7Items: " + (startIndex + 1) + "-" + endIndex);
        lore.add("§7Restored from backup");
        meta.setLore(lore);

        shulkerBoxItem.setItemMeta(meta);

        plugin.getLogger().info("[Restore] Shulker created with " + itemsAdded + " items");

        return shulkerBoxItem;
    }

    private static String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString();
    }
}
