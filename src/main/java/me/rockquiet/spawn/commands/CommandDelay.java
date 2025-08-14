package me.rockquiet.spawn.commands;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.rockquiet.spawn.Spawn;
import me.rockquiet.spawn.SpawnHandler;
import me.rockquiet.spawn.configuration.FileManager;
import me.rockquiet.spawn.configuration.Messages;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class CommandDelay implements Listener {

    private final Map<UUID, WrappedTask> delayTasks = new HashMap<>();

    private final Spawn plugin;
    private final FileManager fileManager;
    private final Messages messageManager;
    private final SpawnHandler spawnHandler;

    public CommandDelay(Spawn plugin,
                        FileManager fileManager,
                        Messages messageManager,
                        SpawnHandler spawnHandler) {
        this.plugin = plugin;
        this.fileManager = fileManager;
        this.messageManager = messageManager;
        this.spawnHandler = spawnHandler;

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public int getDelayTime() {
        YamlConfiguration config = fileManager.getYamlConfig();

        if (config.getBoolean("teleport-delay.enabled")) {
            return config.getInt("teleport-delay.seconds");
        } else {
            return 0;
        }
    }

    public void runDelay(Player player) {
        if (!spawnHandler.spawnExists()) {
            messageManager.sendMessage(player, "no-spawn");
            return;
        }

        UUID playerUUID = player.getUniqueId();
        if (delayTasks.containsKey(playerUUID)) {
            return;
        }

        int delayTime = getDelayTime();
        if (delayTime <= 0) return;

        if (!player.hasPotionEffect(PotionEffectType.BLINDNESS) && fileManager.getYamlConfig().getBoolean("teleport-delay.blindness")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (delayTime + 1) * 20, 0, false, false));
        }

        AtomicInteger delayRemaining = new AtomicInteger(delayTime);
        
        // Use FoliaLib timer for cross-platform compatibility
        plugin.getFoliaLib().getScheduler().runTimer(new Consumer<WrappedTask>() {
            @Override
            public void accept(WrappedTask timerTask) {
                // Store the task so it can be cancelled later
                if (!delayTasks.containsKey(playerUUID)) {
                    delayTasks.put(playerUUID, timerTask);
                }
                
                int remaining = delayRemaining.getAndDecrement();
                if (remaining > 0 && remaining <= delayTime) { // runs until timer reaches 1
                    messageManager.sendMessage(player, "delay-left", "%delay%", String.valueOf(remaining));
                } else if (remaining == 0) { // runs once
                    spawnHandler.teleportPlayer(player);
                    delayTasks.remove(playerUUID);
                    timerTask.cancel();
                }
            }
        }, 0, 20);
    }

    private void clearBlindness(Player player) {
        if (!player.hasPotionEffect(PotionEffectType.BLINDNESS) || !fileManager.getYamlConfig().getBoolean("teleport-delay.blindness")) {
            return;
        }

        // remove the blindness effect only if the duration is equal to or less than the configured delay time (1.10.x +)
        if (Spawn.getServerVersion().getMinor() >= 10 && player.getPotionEffect(PotionEffectType.BLINDNESS).getDuration() <= (getDelayTime() + 1) * 20) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("spawn.bypass.cancel-on-move") || (event.getFrom().distanceSquared(event.getTo()) < 0.01)) {
            return;
        }

        UUID playerUUID = player.getUniqueId();

        if (!delayTasks.containsKey(playerUUID)) {
            return;
        }

        if (fileManager.getYamlConfig().getBoolean("teleport-delay.cancel-on-move")) {
            WrappedTask task = delayTasks.remove(playerUUID);
            if (task != null) {
                task.cancel();
            }

            clearBlindness(player);

            messageManager.sendMessage(player, "teleport-canceled");
        }
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        WrappedTask task = delayTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
            clearBlindness(player);
        }
    }
}
