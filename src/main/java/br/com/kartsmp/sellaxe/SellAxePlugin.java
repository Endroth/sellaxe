package br.com.kartsmp.sellaxe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Map;

public final class SellAxePlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private NamespacedKey axeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        axeKey = new NamespacedKey(this, "sellaxe");
        if (!setupEconomy()) {
            getLogger().severe("Vault ou um provedor de economia não foi encontrado. O SellAxe será desativado.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SellAxe ativado.");
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider();
        return economy != null;
    }

    private String msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&c&lSellAxe &8» &r");
        String body = getConfig().getString("messages." + path, "");
        return color(prefix + body);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private ItemStack createSellAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.setDisplayName(color("&c&lMachadoVendedor"));
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.getPersistentDataContainer().set(axeKey, PersistentDataType.BYTE, (byte) 1);
        meta.setLore(java.util.List.of(
                color("&7Quebre um baú para vender"),
                color("&7automaticamente os itens dentro.")
        ));
        axe.setItemMeta(meta);
        return axe;
    }

    private boolean isSellAxe(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_AXE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(axeKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        if (!sender.hasPermission("sellaxe.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(msg("player-not-found"));
            return true;
        }

        target.getInventory().addItem(createSellAxe()).values().forEach(item ->
                target.getWorld().dropItemNaturally(target.getLocation(), item));
        sender.sendMessage(msg("given").replace("%player%", target.getName()));
        target.sendMessage(msg("received"));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isSellAxe(player.getInventory().getItemInMainHand())) return;

        Block block = event.getBlock();
        if (!(block.getState() instanceof Chest chest)) return;

        Inventory inventory = chest.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean hasItems = false;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) {
            player.sendMessage(msg("no-items"));
            return; // Baú vazio quebra normalmente.
        }

        double total = 0.0;
        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) continue;
            double unitPrice = getConfig().getDouble(
                    "prices." + item.getType().name(),
                    getConfig().getDouble("default-price", 1.0)
            );
            if (unitPrice > 0) total += unitPrice * item.getAmount();
        }

        // Cancela a quebra normal para impedir duplicação dos itens do baú.
        event.setCancelled(true);
        inventory.clear();
        block.setType(Material.AIR, false);
        block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.CHEST));

        if (total > 0) {
            economy.depositPlayer(player, total);
            DecimalFormat format = new DecimalFormat("#,##0.00");
            player.sendMessage(msg("sold").replace("%amount%", format.format(total)));
        } else {
            player.sendMessage(msg("no-items"));
        }
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack first = inventory.getItem(0);
        ItemStack second = inventory.getItem(1);
        if (isSellAxe(first) || isSellAxe(second)) {
            event.setResult(null);
            if (event.getView().getPlayer() instanceof Player player) {
                player.sendMessage(msg("cannot-repair"));
            }
        }
    }
}
