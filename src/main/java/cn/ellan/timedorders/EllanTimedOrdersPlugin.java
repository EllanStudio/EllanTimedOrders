package cn.ellan.timedorders;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import dev.jsinco.brewery.api.brew.BrewManager;
import dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class EllanTimedOrdersPlugin extends JavaPlugin implements Listener {
    private static final String ACTIVE_KEY = "ellan:timedorders:active";
    private static final String CLAIM_KEY = "ellan:timedorders:claim";
    private static final String NOTIFICATION_KEY_PREFIX = "ellan:timedorders:notify:";
    private static final String CHANNEL = "ellan:timedorders:events";
    private static final int[] TASK_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int PAGE_INFO_SLOT = 47;
    private static final int RANDOM_PUBLISH_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 51;
    private static final int CANCEL_ORDER_SLOT = 53;

    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<UUID, Map<String, BossBar>> bossBars = new LinkedHashMap<>();
    private final Set<Integer> expiryInProgress = ConcurrentHashMap.newKeySet();
    private final Map<Integer, OrderState.Active> activeOrders = new LinkedHashMap<>();
    private final Map<Integer, OrderState.Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, Boolean> notificationPreferences = new ConcurrentHashMap<>();
    private final Set<UUID> loadedNotificationPreferences = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> notificationPreferenceVersions = new ConcurrentHashMap<>();
    private final Map<Task.Station, Integer> stationWeights = new EnumMap<>(Task.Station.class);
    private RedisClient redis;
    private ExcellentEconomyAPI economy;
    private TheBrewingProjectApi brewing;
    private BukkitTask autoPublishTask;
    private String serverId;
    private boolean coordinator;
    private boolean autoPublishEnabled;
    private int autoPublishIntervalSeconds;
    private int maxConcurrentOrders;
    private int offerSeconds;
    private String currencyId;
    private String cookedNpcId;
    private String tavernNpcId;
    private String greenhouseNpcId;
    private String casinoBarNpcId;
    private String furnitureNpcId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        RegisteredServiceProvider<ExcellentEconomyAPI> economyProvider =
                Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        RegisteredServiceProvider<TheBrewingProjectApi> brewingProvider =
                Bukkit.getServicesManager().getRegistration(TheBrewingProjectApi.class);
        if (economyProvider == null || brewingProvider == null) {
            getLogger().severe("ExcellentEconomy or TheBrewingProject API is unavailable; disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        economy = economyProvider.getProvider();
        brewing = brewingProvider.getProvider();

        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommand("ellanorder", "艾尔岚限时订单", List.of("限时订单", "timedorder"), new OrderCommand());

        redis.startSubscriber(CHANNEL, this::onRedisEvent,
                exception -> getLogger().log(Level.WARNING, "Redis subscription interrupted; reconnecting", exception));
        restartAutoPublishScheduler();
        refreshStateAsync();

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::validateIntegrations, 100L);
        getLogger().info("EllanTimedOrders enabled on " + serverId + " with " + tasks.size() + " task templates.");
    }

    @Override
    public void onDisable() {
        if (autoPublishTask != null) {
            autoPublishTask.cancel();
            autoPublishTask = null;
        }
        for (Map.Entry<UUID, Map<String, BossBar>> entry : bossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().values().forEach(player::hideBossBar);
            }
        }
        bossBars.clear();
        notificationPreferences.clear();
        loadedNotificationPreferences.clear();
        notificationPreferenceVersions.clear();
        if (redis != null) {
            redis.close();
        }
    }

    private void loadSettings() {
        reloadConfig();
        serverId = getConfig().getString("server-id", "spawn");
        coordinator = getConfig().getBoolean("coordinator", false);
        autoPublishEnabled = getConfig().getBoolean("auto-publish.enabled", true);
        autoPublishIntervalSeconds = Math.max(60, getConfig().getInt("auto-publish.interval-seconds", 1800));
        maxConcurrentOrders = Math.max(1, Math.min(8, getConfig().getInt("max-concurrent-orders", 3)));
        offerSeconds = Math.max(30, getConfig().getInt("offer-seconds", 300));
        currencyId = getConfig().getString("currency-id", "ellan_coin");
        cookedNpcId = getConfig().getString("npcs.cooked", "");
        tavernNpcId = getConfig().getString("npcs.tavern", "");
        greenhouseNpcId = getConfig().getString("npcs.greenhouse", "");
        casinoBarNpcId = getConfig().getString("npcs.casino-bar", "");
        furnitureNpcId = getConfig().getString("npcs.furniture", "");
        stationWeights.clear();
        for (Task.Station station : Task.Station.values()) {
            stationWeights.put(station, Math.max(0,
                    getConfig().getInt("auto-publish.station-weights." + station.name(), 1)));
        }
        redis = new RedisClient(
                getConfig().getString("redis.host", "127.0.0.1"),
                getConfig().getInt("redis.port", 6379),
                getConfig().getString("redis.password", ""),
                getConfig().getInt("redis.timeout-millis", 2000)
        );

        tasks.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("tasks");
        if (section == null) {
            throw new IllegalStateException("No tasks configured");
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection taskSection = section.getConfigurationSection(id);
            if (taskSection == null) {
                continue;
            }
            try {
                Task.Kind kind = Task.Kind.valueOf(taskSection.getString("kind", "CRAFTENGINE").toUpperCase(Locale.ROOT));
                Task.Station station = Task.Station.valueOf(taskSection.getString("station", "COOKED").toUpperCase(Locale.ROOT));
                Material icon = Material.matchMaterial(taskSection.getString("icon", kind == Task.Kind.BREW ? "POTION" : "COOKED_BEEF"));
                Task task = new Task(
                        id,
                        taskSection.getString("name", id),
                        kind,
                        station,
                        taskSection.getString("item-id", ""),
                        Math.max(1, taskSection.getInt("quantity", 1)),
                        Math.max(1, taskSection.getInt("reward", 1)),
                        Math.max(60, taskSection.getInt("duration-seconds", 600)),
                        Math.max(0D, Math.min(1D, taskSection.getDouble("minimum-score", 0D))),
                        icon == null ? Material.PAPER : icon
                );
                tasks.put(id, task);
            } catch (IllegalArgumentException exception) {
                getLogger().log(Level.WARNING, "Invalid task configuration: " + id, exception);
            }
        }
    }

    private void validateIntegrations() {
        if (!economy.hasCurrency(currencyId)) {
            getLogger().severe("ExcellentEconomy currency not found: " + currencyId);
        }
        for (Task task : tasks.values()) {
            if (task.kind() == Task.Kind.CRAFTENGINE) {
                boolean present = hasCraftEngineItem(task.itemId());
                if (!present) {
                    getLogger().warning("CraftEngine item not found for task " + task.id() + ": " + task.itemId());
                }
            } else if (brewing.getRecipeRegistry().getRecipe(task.itemId()).isEmpty()) {
                getLogger().warning("TheBrewingProject recipe not found for task " + task.id() + ": " + task.itemId());
            }
        }
        if (!serverId.equalsIgnoreCase("spawn")) {
            return;
        }
        if (FancyNpcsPlugin.get().getNpcManager().getNpcById(cookedNpcId) == null) {
            getLogger().warning("Cooked-food submission NPC is not loaded: " + cookedNpcId);
        }
        if (FancyNpcsPlugin.get().getNpcManager().getNpcById(tavernNpcId) == null) {
            getLogger().warning("Tavern submission NPC is not loaded: " + tavernNpcId);
        }
        validateNpc("Greenhouse", greenhouseNpcId);
        validateNpc("Casino-bar", casinoBarNpcId);
        validateNpc("Furniture", furnitureNpcId);
    }

    /**
     * CraftEngine renamed its item lookup method between API generations.
     * Reflection keeps the integration linkable with the older server plugin
     * while compiling against the published API used by the 26.3 build.
     */
    private boolean hasCraftEngineItem(String itemId) {
        Object manager = BukkitItemManager.instance();
        net.momirealms.craftengine.core.util.Key key =
                net.momirealms.craftengine.core.util.Key.of(itemId);
        for (String methodName : new String[] {"getCustomItem", "getItemDefinition"}) {
            try {
                java.lang.reflect.Method method = manager.getClass().getMethod(
                        methodName, net.momirealms.craftengine.core.util.Key.class);
                Object result = method.invoke(manager, key);
                return result instanceof java.util.Optional<?> optional && optional.isPresent();
            } catch (NoSuchMethodException ignored) {
                // Try the other API generation.
            } catch (ReflectiveOperationException exception) {
                getLogger().log(Level.FINE, "CraftEngine item lookup failed for " + itemId, exception);
                return false;
            }
        }
        getLogger().warning("CraftEngine item lookup API is unavailable; cannot validate " + itemId);
        return false;
    }

    private void validateNpc(String label, String npcId) {
        if (npcId.isBlank() || FancyNpcsPlugin.get().getNpcManager().getNpcById(npcId) == null) {
            getLogger().warning(label + " submission NPC is not loaded: " + npcId);
        }
    }

    private static String activeKey(int slot) {
        return slot == 0 ? ACTIVE_KEY : ACTIVE_KEY + ":" + slot;
    }

    private static String claimKey(int slot) {
        return slot == 0 ? CLAIM_KEY : CLAIM_KEY + ":" + slot;
    }

    private void restartAutoPublishScheduler() {
        if (autoPublishTask != null) {
            autoPublishTask.cancel();
            autoPublishTask = null;
        }
        if (!coordinator || !autoPublishEnabled || tasks.isEmpty()) {
            return;
        }
        long intervalTicks = autoPublishIntervalSeconds * 20L;
        autoPublishTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::publishRandomTask, intervalTicks, intervalTicks);
        getLogger().info("Automatic random orders enabled every " + autoPublishIntervalSeconds
                + " seconds with " + maxConcurrentOrders + " concurrent slots.");
    }

    private void publishRandomTask() {
        if (!coordinator || !autoPublishEnabled || tasks.isEmpty()) {
            return;
        }
        try {
            List<OrderState.Active> existing = readActiveOrders().values().stream().toList();
            if (existing.size() >= maxConcurrentOrders) {
                return;
            }
            Set<String> activeTaskIds = existing.stream().map(OrderState.Active::taskId)
                    .collect(java.util.stream.Collectors.toSet());
            List<Task> pool = tasks.values().stream()
                    .filter(task -> !activeTaskIds.contains(task.id()))
                    .toList();
            if (pool.isEmpty()) {
                pool = new ArrayList<>(tasks.values());
            }
            Task task = chooseRandomTask(pool);
            tryPublishTask(task, null);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Could not select a random order", exception);
        }
    }

    private Task chooseRandomTask(List<Task> pool) {
        Map<Task.Station, List<Task>> byStation = new EnumMap<>(Task.Station.class);
        for (Task task : pool) {
            byStation.computeIfAbsent(task.station(), ignored -> new ArrayList<>()).add(task);
        }
        int totalWeight = byStation.keySet().stream().mapToInt(station -> stationWeights.getOrDefault(station, 1)).sum();
        if (totalWeight <= 0) {
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (Map.Entry<Task.Station, List<Task>> entry : byStation.entrySet()) {
            roll -= stationWeights.getOrDefault(entry.getKey(), 1);
            if (roll < 0) {
                List<Task> stationPool = entry.getValue();
                return stationPool.get(ThreadLocalRandom.current().nextInt(stationPool.size()));
            }
        }
        return pool.getLast();
    }

    private void tryPublishTaskAsync(Task task, @Nullable Player admin) {
        try {
            tryPublishTask(task, admin);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not publish task " + task.id(), exception);
            if (admin != null) {
                tellMain(admin, Component.text("发布失败：Redis 连接异常。", NamedTextColor.RED));
            }
        }
    }

    private void tryPublishTask(Task task, @Nullable Player admin) throws IOException {
        for (int slot = 0; slot < maxConcurrentOrders; slot++) {
            long now = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            OrderState.Active next = new OrderState.Active(nonce, task.id(), now, now + offerSeconds * 1000L);
            if (!redis.setIfAbsent(activeKey(slot), next.encode(), (offerSeconds + 120L) * 1000L)) {
                continue;
            }
            redis.publish(CHANNEL, "PUBLISHED|" + nonce + "|" + task.id() + "|" + slot);
            if (admin != null) {
                tellMain(admin, Component.text("订单已发布。", NamedTextColor.GREEN));
            } else {
                getLogger().info("Automatically published order: " + task.id());
            }
            return;
        }
        if (admin != null) {
            tellMain(admin, Component.text("已达并发上限，请等待某张订单结束或撤回。", NamedTextColor.RED));
        }
    }

    private void refreshStateAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Map<Integer, OrderState.Active> nextActive = readActiveOrders();
                Map<Integer, OrderState.Claim> nextClaims = readClaims();
                Bukkit.getScheduler().runTask(this, () -> applyState(nextActive, nextClaims));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING, "Could not refresh order state from Redis", exception);
            }
        });
    }

    private Map<Integer, OrderState.Active> readActiveOrders() throws IOException {
        Map<Integer, OrderState.Active> result = new LinkedHashMap<>();
        for (int slot = 0; slot < maxConcurrentOrders; slot++) {
            OrderState.Active value = OrderState.Active.parse(redis.get(activeKey(slot)));
            if (value != null) {
                result.put(slot, value);
            }
        }
        return result;
    }

    private Map<Integer, OrderState.Claim> readClaims() throws IOException {
        Map<Integer, OrderState.Claim> result = new LinkedHashMap<>();
        for (int slot = 0; slot < maxConcurrentOrders; slot++) {
            OrderState.Claim value = OrderState.Claim.parse(redis.get(claimKey(slot)));
            if (value != null) {
                result.put(slot, value);
            }
        }
        return result;
    }

    private void applyState(Map<Integer, OrderState.Active> nextActive,
                            Map<Integer, OrderState.Claim> nextClaims) {
        activeOrders.clear();
        activeOrders.putAll(nextActive);
        claims.clear();
        claims.putAll(nextClaims);
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateBossBarFor(player);
        }
    }

    private void onRedisEvent(String payload) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> announceRedisEvent(payload));
        refreshStateAsync();
    }

    private void announceRedisEvent(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length == 0) {
            return;
        }
        Task task = parts.length > 2 ? tasks.get(parts[2]) : null;
        switch (parts[0]) {
            case "PUBLISHED" -> {
                if (task != null && parts.length > 1) {
                    broadcastPublished(task, parts[1]);
                }
            }
            case "CLAIMED" -> {
                if (task != null && parts.length > 3) {
                    broadcast(Component.text("[限时订单] ", NamedTextColor.GOLD)
                            .append(Component.text(parts[3], NamedTextColor.YELLOW))
                            .append(Component.text(" 接下了“" + task.name() + "”订单。", NamedTextColor.GRAY)));
                }
            }
            case "COMPLETED" -> {
                if (task != null && parts.length > 4) {
                    broadcast(Component.text("[订单完成] ", NamedTextColor.GREEN)
                            .append(Component.text(parts[3], NamedTextColor.WHITE))
                            .append(Component.text(" 已向" + task.station().displayName() + "送达 " + task.name()
                                    + "，获得 " + parts[4] + " 艾尔岚金币。", NamedTextColor.GRAY)));
                }
            }
            case "FAILED" -> {
                if (task != null && parts.length > 3) {
                    broadcast(Component.text("[订单超时] ", NamedTextColor.RED)
                            .append(Component.text(parts[3] + " 未能及时送达“" + task.name() + "”。", NamedTextColor.GRAY)));
                }
                scheduleReplacement();
            }
            case "EXPIRED" -> {
                if (task != null) {
                    broadcast(Component.text("[限时订单] “" + task.name() + "”无人接取，订单已撤回。", NamedTextColor.DARK_GRAY));
                }
                scheduleReplacement();
            }
            case "CANCELLED" -> {
                if (task != null) {
                    broadcast(Component.text("[限时订单] “" + task.name() + "”已由管理员撤回。", NamedTextColor.DARK_GRAY));
                }
            }
            default -> {
            }
        }
    }

    private void scheduleReplacement() {
        if (coordinator && autoPublishEnabled) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::publishRandomTask, 60L);
        }
    }

    private void broadcastPublished(Task task, String nonce) {
        Component first = Component.text("[限时订单] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(task.station().displayName() + "急需 ", NamedTextColor.YELLOW))
                .append(Component.text(task.name() + " ×" + task.quantity(), NamedTextColor.WHITE, TextDecoration.BOLD));
        Component second = Component.text("送达时限：" + formatDuration(task.durationSeconds())
                        + "　报酬：" + task.reward() + " 艾尔岚金币", NamedTextColor.GRAY);
        Component accept = Component.text("[点击接取订单]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/ellanorder accept " + nonce))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "接取后开始倒计时。交付地点：" + task.station().displayName() + task.qualityText(), NamedTextColor.YELLOW)));
        broadcast(first);
        broadcast(second.append(Component.space()).append(accept));
    }

    private void broadcast(Component component) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!notificationsEnabled(player)) {
                continue;
            }
            player.sendMessage(component);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.15F);
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    private static String notificationKey(UUID playerId) {
        return NOTIFICATION_KEY_PREFIX + playerId;
    }

    private boolean notificationsEnabled(Player player) {
        return notificationPreferences.getOrDefault(player.getUniqueId(), true);
    }

    private void loadNotificationPreferenceAsync(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!isEnabled()) {
                return;
            }
            boolean enabled = true;
            try {
                enabled = !"false".equalsIgnoreCase(redis.get(notificationKey(playerId)));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING,
                        "Could not load order notification preference for " + playerId, exception);
                return;
            }
            boolean loadedValue = enabled;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline() || loadedNotificationPreferences.contains(playerId)) {
                    return;
                }
                notificationPreferences.put(playerId, loadedValue);
                loadedNotificationPreferences.add(playerId);
            });
        });
    }

    private void setNotificationPreference(Player player, boolean enabled) {
        UUID playerId = player.getUniqueId();
        long version = notificationPreferenceVersions.merge(playerId, 1L, Long::sum);
        notificationPreferences.put(playerId, enabled);
        loadedNotificationPreferences.add(playerId);
        player.sendMessage(notificationStatus(player, enabled));
        player.sendMessage(Component.text(
                "仅影响全服订单公告；接单、倒计时和交付反馈仍会正常显示。",
                NamedTextColor.DARK_GRAY
        ));

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                redis.setPersistent(notificationKey(playerId), Boolean.toString(enabled));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING,
                        "Could not save order notification preference for " + playerId, exception);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!Objects.equals(notificationPreferenceVersions.get(playerId), version)) {
                        return;
                    }
                    notificationPreferences.put(playerId, !enabled);
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                "保存订单推送设置失败，请稍后重试。",
                                NamedTextColor.RED
                        ));
                    }
                });
            }
        });
    }

    private static Component notificationStatus(Player player, boolean enabled) {
        return Component.text("限时订单公屏推送：", NamedTextColor.GOLD)
                .append(Component.text(enabled ? "已开启" : "已关闭",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void tick() {
        updateBossBars();
        if (!coordinator) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, OrderState.Active> entry : new ArrayList<>(activeOrders.entrySet())) {
            int slot = entry.getKey();
            OrderState.Active currentActive = entry.getValue();
            OrderState.Claim currentClaim = claims.get(slot);
            if (currentClaim == null && now >= currentActive.offerExpiresAt()) {
                expireAsync(slot, false, currentActive, null);
            } else if (currentClaim != null && now >= currentClaim.deadline()) {
                expireAsync(slot, true, currentActive, currentClaim);
            }
        }
    }

    private void expireAsync(int slot, boolean failed, OrderState.Active expectedActive,
                             @Nullable OrderState.Claim expectedClaim) {
        if (!expiryInProgress.add(slot)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                OrderState.Active remoteActive = OrderState.Active.parse(redis.get(activeKey(slot)));
                OrderState.Claim remoteClaim = OrderState.Claim.parse(redis.get(claimKey(slot)));
                if (remoteActive == null || !remoteActive.nonce().equals(expectedActive.nonce())) {
                    return;
                }
                if (failed && (remoteClaim == null || expectedClaim == null
                        || !remoteClaim.nonce().equals(expectedClaim.nonce()))) {
                    return;
                }
                if (!failed && remoteClaim != null) {
                    return;
                }
                redis.delete(activeKey(slot), claimKey(slot));
                String event = failed
                        ? "FAILED|" + expectedActive.nonce() + "|" + expectedActive.taskId() + "|" + remoteClaim.playerName()
                        : "EXPIRED|" + expectedActive.nonce() + "|" + expectedActive.taskId();
                redis.publish(CHANNEL, event);
            } catch (IOException exception) {
                getLogger().log(Level.WARNING, "Could not expire order", exception);
            } finally {
                expiryInProgress.remove(slot);
            }
        });
    }

    private void updateBossBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateBossBarFor(player);
        }
    }

    private void updateBossBarFor(Player player) {
        Map<String, BossBar> playerBars = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>());
        Set<String> visible = ConcurrentHashMap.newKeySet();
        for (Map.Entry<Integer, OrderState.Active> entry : activeOrders.entrySet()) {
            OrderState.Active currentActive = entry.getValue();
            OrderState.Claim currentClaim = claims.get(entry.getKey());
            if (currentClaim == null || !currentClaim.nonce().equals(currentActive.nonce())
                    || !currentClaim.playerId().equals(player.getUniqueId())) {
                continue;
            }
            Task task = tasks.get(currentActive.taskId());
            if (task == null) {
                continue;
            }
            visible.add(currentActive.nonce());
            long remainingMillis = Math.max(0L, currentClaim.deadline() - System.currentTimeMillis());
            float progress = Math.max(0F, Math.min(1F, remainingMillis / (task.durationSeconds() * 1000F)));
            BossBar bar = playerBars.computeIfAbsent(currentActive.nonce(), ignored -> {
                BossBar created = BossBar.bossBar(Component.empty(), 1F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                player.showBossBar(created);
                return created;
            });
            bar.progress(progress);
            bar.name(Component.text("限时订单：" + task.name() + " ×" + task.quantity()
                    + "　剩余 " + formatDuration((int) Math.ceil(remainingMillis / 1000D)), NamedTextColor.WHITE));
            bar.color(remainingMillis <= 60_000L ? BossBar.Color.RED : BossBar.Color.YELLOW);
        }
        playerBars.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) {
                return false;
            }
            player.hideBossBar(entry.getValue());
            return true;
        });
        if (playerBars.isEmpty()) {
            bossBars.remove(player.getUniqueId());
        }
    }

    private void removeBossBar(Player player) {
        Map<String, BossBar> bars = bossBars.remove(player.getUniqueId());
        if (bars != null) {
            bars.values().forEach(player::hideBossBar);
        }
    }

    private void openAdminMenu(Player player) {
        openAdminMenu(player, 1);
    }

    private void openAdminMenu(Player player, int requestedPage) {
        List<Task> taskList = new ArrayList<>(tasks.values());
        int maxPage = Math.max(1, (taskList.size() + TASK_SLOTS.length - 1) / TASK_SLOTS.length);
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        AdminHolder holder = new AdminHolder(page, maxPage);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("限时订单管理 · " + page + "/" + maxPage, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        int startIndex = (page - 1) * TASK_SLOTS.length;
        for (int index = 0; index < TASK_SLOTS.length && startIndex + index < taskList.size(); index++) {
            Task task = taskList.get(startIndex + index);
            int slot = TASK_SLOTS[index];
            holder.taskSlots.put(slot, task.id());
            inventory.setItem(slot, taskIcon(task));
        }

        List<Component> statusLore = new ArrayList<>();
        if (activeOrders.isEmpty()) {
            statusLore.add(Component.text("当前没有正在进行的订单", NamedTextColor.GRAY));
        } else {
            for (Map.Entry<Integer, OrderState.Active> entry : activeOrders.entrySet()) {
                Task activeTask = tasks.get(entry.getValue().taskId());
                if (activeTask == null) {
                    continue;
                }
                OrderState.Claim activeClaim = claims.get(entry.getKey());
                String state = activeClaim == null ? "等待接取" : activeClaim.playerName() + " 配送中";
                statusLore.add(Component.text("• " + activeTask.name() + " ×" + activeTask.quantity()
                        + "（" + state + "）", NamedTextColor.GRAY));
            }
        }
        inventory.setItem(4, namedItem(Material.CLOCK, Component.text("当前订单 "
                + activeOrders.size() + "/" + maxConcurrentOrders, NamedTextColor.GOLD), statusLore));
        inventory.setItem(PREVIOUS_PAGE_SLOT, page > 1
                ? namedItem(Material.ARROW, Component.text("上一页", NamedTextColor.YELLOW), List.of(
                Component.text("查看第 " + (page - 1) + " 页任务", NamedTextColor.GRAY)))
                : namedItem(Material.GRAY_DYE, Component.text("已经是第一页", NamedTextColor.DARK_GRAY), List.of()));
        inventory.setItem(PAGE_INFO_SLOT, namedItem(Material.BOOK, Component.text("第 " + page + " / " + maxPage + " 页", NamedTextColor.AQUA), List.of(
                Component.text("本页显示 " + Math.min(TASK_SLOTS.length, taskList.size() - startIndex) + " 项任务", NamedTextColor.GRAY))));
        inventory.setItem(RANDOM_PUBLISH_SLOT, namedItem(Material.NETHER_STAR, Component.text("随机发布", NamedTextColor.GREEN), List.of(
                Component.text("从任务池随机选择一项并全服发布", NamedTextColor.GRAY),
                Component.text("同时最多存在 " + maxConcurrentOrders + " 张订单", NamedTextColor.DARK_GRAY)
        )));
        inventory.setItem(NEXT_PAGE_SLOT, page < maxPage
                ? namedItem(Material.ARROW, Component.text("下一页", NamedTextColor.YELLOW), List.of(
                Component.text("查看第 " + (page + 1) + " 页任务", NamedTextColor.GRAY)))
                : namedItem(Material.GRAY_DYE, Component.text("已经是最后一页", NamedTextColor.DARK_GRAY), List.of()));
        inventory.setItem(CANCEL_ORDER_SLOT, namedItem(Material.BARRIER, Component.text("撤回最早订单", NamedTextColor.RED), List.of(
                Component.text("已接取的订单也会被立即终止", NamedTextColor.GRAY),
                Component.text("也可用 /ellanorder cancel <订单编号>", NamedTextColor.DARK_GRAY)
        )));
        player.openInventory(inventory);
    }

    private ItemStack taskIcon(Task task) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("需求：" + task.name() + " ×" + task.quantity(), NamedTextColor.GRAY));
        lore.add(Component.text("交付：" + task.station().displayName(), NamedTextColor.GRAY));
        lore.add(Component.text("时限：" + formatDuration(task.durationSeconds()), NamedTextColor.GRAY));
        lore.add(Component.text("报酬：" + task.reward() + " 艾尔岚金币", NamedTextColor.GOLD));
        if (task.kind() == Task.Kind.BREW) {
            lore.add(Component.text("要求：已封口" + task.qualityText(), NamedTextColor.AQUA));
        }
        lore.add(Component.empty());
        lore.add(Component.text("点击发布", NamedTextColor.GREEN));
        return namedItem(task.icon(), Component.text(task.name(), NamedTextColor.YELLOW), lore);
    }

    private static ItemStack namedItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private void publishTask(Player admin, Task task) {
        admin.closeInventory();
        admin.sendMessage(Component.text("正在发布“" + task.name() + "”……", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> tryPublishTaskAsync(task, admin));
    }

    private void acceptTask(Player player, String nonce) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                int slot = findSlotByNonce(nonce);
                if (slot < 0) {
                    tellMain(player, Component.text("这张订单已经失效。", NamedTextColor.RED));
                    return;
                }
                OrderState.Active remoteActive = OrderState.Active.parse(redis.get(activeKey(slot)));
                if (remoteActive == null || !remoteActive.nonce().equals(nonce)
                        || System.currentTimeMillis() >= remoteActive.offerExpiresAt()) {
                    tellMain(player, Component.text("这张订单已经失效。", NamedTextColor.RED));
                    return;
                }
                Task task = tasks.get(remoteActive.taskId());
                if (task == null) {
                    tellMain(player, Component.text("订单模板不存在，请联系管理员。", NamedTextColor.RED));
                    return;
                }
                OrderState.Claim existingClaim = OrderState.Claim.parse(redis.get(claimKey(slot)));
                if (existingClaim != null) {
                    String message = existingClaim.playerId().equals(player.getUniqueId())
                            ? "你已经接取了这张订单。"
                            : "手慢了一步，这张订单已被 " + existingClaim.playerName() + " 接取。";
                    tellMain(player, Component.text(message, NamedTextColor.RED));
                    return;
                }
                long now = System.currentTimeMillis();
                OrderState.Claim nextClaim = new OrderState.Claim(nonce, player.getUniqueId(), player.getName(),
                        now, now + task.durationSeconds() * 1000L);
                if (!redis.setIfAbsent(claimKey(slot), nextClaim.encode(), (task.durationSeconds() + 120L) * 1000L)) {
                    tellMain(player, Component.text("手慢了一步，这张订单刚刚被别人接走了。", NamedTextColor.RED));
                    return;
                }
                redis.expire(activeKey(slot), (task.durationSeconds() + 120L) * 1000L);
                redis.publish(CHANNEL, "CLAIMED|" + nonce + "|" + task.id() + "|" + player.getName());
                tellMain(player, Component.text("已接取“" + task.name() + "”！请在 "
                        + formatDuration(task.durationSeconds()) + " 内送往" + task.station().displayName() + "。", NamedTextColor.GREEN));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING, "Could not accept order", exception);
                tellMain(player, Component.text("暂时无法接取订单，请稍后重试。", NamedTextColor.RED));
            }
        });
    }

    private int findSlotByNonce(String nonce) throws IOException {
        for (int slot = 0; slot < maxConcurrentOrders; slot++) {
            OrderState.Active candidate = OrderState.Active.parse(redis.get(activeKey(slot)));
            if (candidate != null && candidate.nonce().equals(nonce)) {
                return slot;
            }
        }
        return -1;
    }

    private void submitAtNpc(Player player, Task.Station station) {
        try {
            List<Submission> candidates = new ArrayList<>();
            boolean hasAnotherStation = false;
            for (int slot = 0; slot < maxConcurrentOrders; slot++) {
                OrderState.Active remoteActive = OrderState.Active.parse(redis.get(activeKey(slot)));
                OrderState.Claim remoteClaim = OrderState.Claim.parse(redis.get(claimKey(slot)));
                if (remoteActive == null || remoteClaim == null
                        || !remoteClaim.nonce().equals(remoteActive.nonce())
                        || !remoteClaim.playerId().equals(player.getUniqueId())) {
                    continue;
                }
                Task task = tasks.get(remoteActive.taskId());
                if (task == null) {
                    continue;
                }
                if (task.station() == station) {
                    candidates.add(new Submission(slot, remoteActive, remoteClaim, task));
                } else {
                    hasAnotherStation = true;
                }
            }
            candidates.sort(Comparator.comparingLong(value -> value.claim().deadline()));
            for (Submission candidate : candidates) {
                if (System.currentTimeMillis() < candidate.claim().deadline()
                        && hasRequiredItems(player, candidate.task())) {
                    completeSubmission(player, candidate);
                    return;
                }
            }
            if (!candidates.isEmpty()) {
                Submission candidate = candidates.getFirst();
                if (System.currentTimeMillis() >= candidate.claim().deadline()) {
                    player.sendMessage(Component.text("这里可提交的订单已经超时。", NamedTextColor.RED));
                    return;
                }
                Task task = candidate.task();
                String extra = task.kind() == Task.Kind.BREW ? "（酒水必须封口" + task.qualityText() + "）" : "";
                player.sendMessage(Component.text("数量不足：需要 " + task.name() + " ×" + task.quantity() + extra, NamedTextColor.RED));
                return;
            }
            String message = hasAnotherStation
                    ? "你的订单需要送往其他交付地点。"
                    : "你目前没有可在这里提交的限时订单。";
            player.sendMessage(Component.text(message, NamedTextColor.GRAY));
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not submit order for " + player.getName(), exception);
            player.sendMessage(Component.text("订单服务暂时不可用，请稍后重试。", NamedTextColor.RED));
        }
    }

    private void completeSubmission(Player player, Submission submission) throws IOException {
        Task task = submission.task();
        String doneKey = "ellan:timedorders:done:" + submission.active().nonce();
        if (!redis.setIfAbsent(doneKey, player.getUniqueId().toString(), 600_000L)) {
            player.sendMessage(Component.text("这张订单正在结算，请勿重复提交。", NamedTextColor.YELLOW));
            return;
        }
        List<ItemStack> removed = removeRequiredItems(player, task);
        boolean deposited = economy.deposit(player, currencyId, task.reward());
        if (!deposited) {
            restoreItems(player, removed);
            redis.delete(doneKey);
            player.sendMessage(Component.text("金币结算失败，物品已经退回，请联系管理员。", NamedTextColor.RED));
            return;
        }
        redis.delete(activeKey(submission.slot()), claimKey(submission.slot()));
        redis.publish(CHANNEL, "COMPLETED|" + submission.active().nonce() + "|" + task.id()
                + "|" + player.getName() + "|" + task.reward());
        player.sendMessage(Component.text("交付成功，获得 " + task.reward() + " 艾尔岚金币！", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1F, 1.1F);
    }

    private boolean hasRequiredItems(Player player, Task task) {
        int amount = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, task)) {
                amount += stack.getAmount();
                if (amount >= task.quantity()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ItemStack> removeRequiredItems(Player player, Task task) {
        List<ItemStack> removed = new ArrayList<>();
        int remaining = task.quantity();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack, task)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            ItemStack taken = stack.clone();
            taken.setAmount(take);
            removed.add(taken);
            if (take == stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, stack);
            }
            remaining -= take;
        }
        return removed;
    }

    private boolean matches(@Nullable ItemStack stack, Task task) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (task.kind() == Task.Kind.CRAFTENGINE) {
            return BukkitItemManager.instance().wrap(stack).customId()
                    .map(net.momirealms.craftengine.core.util.Key::asString)
                    .filter(task.itemId()::equalsIgnoreCase)
                    .isPresent();
        }
        BrewManager<ItemStack> manager = brewing.getBrewManager();
        String brewName = manager.brewName(stack).orElse(null);
        if (brewName == null || !normalizedId(brewName).equals(normalizedId(task.itemId()))) {
            return false;
        }
        boolean sealed = manager.fromItem(stack).isEmpty();
        double score = manager.brewScore(stack).orElse(-1D);
        return sealed && score >= task.minimumScore();
    }

    private static String normalizedId(String value) {
        int separator = value.indexOf(':');
        return (separator >= 0 ? value.substring(separator + 1) : value).toLowerCase(Locale.ROOT);
    }

    private static void restoreItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            Collection<ItemStack> leftovers = player.getInventory().addItem(item).values();
            for (ItemStack leftover : leftovers) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void cancelCurrent(Player admin) {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Map.Entry<Integer, OrderState.Active> oldest = readActiveOrders().entrySet().stream()
                        .min(Comparator.comparingLong(entry -> entry.getValue().publishedAt()))
                        .orElse(null);
                if (oldest == null) {
                    tellMain(admin, Component.text("当前没有可以撤回的订单。", NamedTextColor.GRAY));
                    return;
                }
                cancelSlot(oldest.getKey(), oldest.getValue());
                tellMain(admin, Component.text("订单已撤回。", NamedTextColor.GREEN));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING, "Could not cancel order", exception);
                tellMain(admin, Component.text("撤回失败：Redis 连接异常。", NamedTextColor.RED));
            }
        });
    }

    private void cancelByNonce(Player admin, String nonce) {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                int slot = findSlotByNonce(nonce);
                if (slot < 0) {
                    tellMain(admin, Component.text("找不到这张订单。", NamedTextColor.RED));
                    return;
                }
                OrderState.Active remoteActive = OrderState.Active.parse(redis.get(activeKey(slot)));
                if (remoteActive == null) {
                    tellMain(admin, Component.text("这张订单已经结束。", NamedTextColor.GRAY));
                    return;
                }
                cancelSlot(slot, remoteActive);
                tellMain(admin, Component.text("订单已撤回。", NamedTextColor.GREEN));
            } catch (IOException exception) {
                getLogger().log(Level.WARNING, "Could not cancel order", exception);
                tellMain(admin, Component.text("撤回失败：Redis 连接异常。", NamedTextColor.RED));
            }
        });
    }

    private void cancelSlot(int slot, OrderState.Active remoteActive) throws IOException {
        redis.delete(activeKey(slot), claimKey(slot));
        redis.publish(CHANNEL, "CANCELLED|" + remoteActive.nonce() + "|" + remoteActive.taskId());
    }

    private void tellStatus(Player player) {
        player.sendMessage(notificationStatus(player, notificationsEnabled(player)));
        if (activeOrders.isEmpty()) {
            player.sendMessage(Component.text("当前没有限时订单。", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("当前限时订单：", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Map.Entry<Integer, OrderState.Active> entry : activeOrders.entrySet()) {
            Task task = tasks.get(entry.getValue().taskId());
            if (task == null) {
                continue;
            }
            OrderState.Claim activeClaim = claims.get(entry.getKey());
            String state = activeClaim == null ? "等待接取" : activeClaim.playerName() + " 配送中";
            player.sendMessage(Component.text("• " + task.station().displayName() + "：" + task.name()
                    + " ×" + task.quantity() + "（" + state + "）", NamedTextColor.YELLOW));
        }
    }

    private static String formatDuration(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        int minutes = safeSeconds / 60;
        int remainder = safeSeconds % 60;
        if (minutes == 0) {
            return remainder + "秒";
        }
        return remainder == 0 ? minutes + "分钟" : minutes + "分" + remainder + "秒";
    }

    private void tellMain(Player player, Component component) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNpcInteract(NpcInteractEvent event) {
        if (event.getInteractionType() == ActionTrigger.LEFT_CLICK) {
            return;
        }
        String npcId = event.getNpc().getData().getId();
        if (npcId.equalsIgnoreCase(cookedNpcId)) {
            submitAtNpc(event.getPlayer(), Task.Station.COOKED);
        } else if (npcId.equalsIgnoreCase(tavernNpcId)) {
            submitAtNpc(event.getPlayer(), Task.Station.TAVERN);
        } else if (npcId.equalsIgnoreCase(greenhouseNpcId)) {
            submitAtNpc(event.getPlayer(), Task.Station.GREENHOUSE);
        } else if (npcId.equalsIgnoreCase(casinoBarNpcId)) {
            submitAtNpc(event.getPlayer(), Task.Station.CASINO_BAR);
        } else if (npcId.equalsIgnoreCase(furnitureNpcId)) {
            submitAtNpc(event.getPlayer(), Task.Station.FURNITURE);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadNotificationPreferenceAsync(event.getPlayer());
        Bukkit.getScheduler().runTaskLater(this, () -> updateBossBarFor(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        notificationPreferences.remove(playerId);
        loadedNotificationPreferences.remove(playerId);
        notificationPreferenceVersions.remove(playerId);
        removeBossBar(event.getPlayer());
    }

    @EventHandler
    public void onAdminMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int clickedSlot = event.getRawSlot();
        if (clickedSlot == PREVIOUS_PAGE_SLOT && holder.page > 1) {
            openAdminMenu(player, holder.page - 1);
            return;
        }
        if (clickedSlot == NEXT_PAGE_SLOT && holder.page < holder.maxPage) {
            openAdminMenu(player, holder.page + 1);
            return;
        }
        String taskId = holder.taskSlots.get(clickedSlot);
        if (taskId != null) {
            Task task = tasks.get(taskId);
            if (task != null) {
                publishTask(player, task);
            }
            return;
        }
        if (clickedSlot == RANDOM_PUBLISH_SLOT && !tasks.isEmpty()) {
            List<Task> pool = new ArrayList<>(tasks.values());
            publishTask(player, chooseRandomTask(pool));
        } else if (clickedSlot == CANCEL_ORDER_SLOT) {
            cancelCurrent(player);
        }
    }

    private final class OrderCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("This command must be run by a player.");
                return;
            }
            if (args.length == 0) {
                tellStatus(player);
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "admin" -> {
                    if (!player.hasPermission("ellanorders.admin")) {
                        player.sendMessage(Component.text("你没有权限打开管理面板。", NamedTextColor.RED));
                        return;
                    }
                    openAdminMenu(player);
                }
                case "accept" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("这张订单链接不完整。", NamedTextColor.RED));
                        return;
                    }
                    acceptTask(player, args[1]);
                }
                case "notify" -> handleNotificationCommand(player, args);
                case "cancel" -> {
                    if (!player.hasPermission("ellanorders.admin")) {
                        player.sendMessage(Component.text("你没有权限撤回订单。", NamedTextColor.RED));
                        return;
                    }
                    if (args.length >= 2) {
                        cancelByNonce(player, args[1]);
                    } else {
                        cancelCurrent(player);
                    }
                }
                case "reload" -> {
                    if (!player.hasPermission("ellanorders.admin")) {
                        player.sendMessage(Component.text("你没有权限重载配置。", NamedTextColor.RED));
                        return;
                    }
                    if (!activeOrders.isEmpty()) {
                        player.sendMessage(Component.text("请先等待当前订单结束或将其撤回，再重载配置。", NamedTextColor.RED));
                        return;
                    }
                    if (redis != null) {
                        redis.close();
                    }
                    loadSettings();
                    redis.startSubscriber(CHANNEL, EllanTimedOrdersPlugin.this::onRedisEvent,
                            exception -> getLogger().log(Level.WARNING, "Redis subscription interrupted; reconnecting", exception));
                    restartAutoPublishScheduler();
                    refreshStateAsync();
                    player.sendMessage(Component.text("限时订单配置已重载。", NamedTextColor.GREEN));
                }
                default -> tellStatus(player);
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length == 1) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("notify");
                if (source.getSender().hasPermission("ellanorders.admin")) {
                    suggestions.add("admin");
                    suggestions.add("cancel");
                    suggestions.add("reload");
                }
                return suggestions;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("notify")) {
                return List.of("on", "off", "status");
            }
            return Collections.emptyList();
        }

        @Override
        public @Nullable String permission() {
            return null;
        }
    }

    private void handleNotificationCommand(Player player, String[] args) {
        if (args.length > 2) {
            player.sendMessage(Component.text("用法：/ellanorder notify [on|off|status]", NamedTextColor.RED));
            return;
        }
        boolean current = notificationsEnabled(player);
        if (args.length == 1) {
            setNotificationPreference(player, !current);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "enable", "open", "开启" -> setNotificationPreference(player, true);
            case "off", "disable", "close", "关闭" -> setNotificationPreference(player, false);
            case "status", "状态" -> player.sendMessage(notificationStatus(player, current));
            default -> player.sendMessage(Component.text(
                    "用法：/ellanorder notify [on|off|status]",
                    NamedTextColor.RED
            ));
        }
    }

    private record Submission(int slot, OrderState.Active active, OrderState.Claim claim, Task task) {
    }

    private static final class AdminHolder implements InventoryHolder {
        private final int page;
        private final int maxPage;
        private final Map<Integer, String> taskSlots = new LinkedHashMap<>();
        private Inventory inventory;

        private AdminHolder(int page, int maxPage) {
            this.page = page;
            this.maxPage = maxPage;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(inventory);
        }
    }
}
