package su.nightexpress.excellentcrates.util;

import org.bukkit.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Keys;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.util.*;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.text.night.NightMessage;
import su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers;
import su.nightexpress.nightcore.util.wrapper.UniParticle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class CrateUtils {

    public static final int REWARD_ITEMS_LIMIT = 27;
    private static final int COMMANDS_PER_TICK = 8;
    private static final String COMMAND_OUTBOX_FILE = "pending-commands.yml";
    private static final Map<UUID, CommandQueue> COMMAND_QUEUES = new ConcurrentHashMap<>();
    private static final Set<UUID> ACTIVE_COMMAND_QUEUES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> PAUSED_COMMAND_QUEUES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RESUMING_COMMAND_QUEUES = ConcurrentHashMap.newKeySet();
    private static final Object COMMAND_OUTBOX_LOCK = new Object();
    private static final Object COMMAND_INGRESS_LOCK = new Object();
    private static volatile boolean commandQueuesShutdown;
    private static volatile Thread commandQueueShutdownThread;
    private static boolean commandQueueIngressClosed = true;
    private static int commandQueueWriterCount;

    public static void startCommandQueues() {
        synchronized (COMMAND_INGRESS_LOCK) {
            commandQueuesShutdown = false;
            commandQueueShutdownThread = null;
            commandQueueIngressClosed = false;
        }
    }

    public static void beginCommandQueueShutdown() {
        synchronized (COMMAND_INGRESS_LOCK) {
            commandQueuesShutdown = true;
        }
        ACTIVE_COMMAND_QUEUES.clear();
    }

    public static void dispatchCommandsOrdered(@NotNull CratesPlugin plugin,
                                               @NotNull Player player,
                                               @NotNull List<String> commands) {
        if (commands.isEmpty()) return;
        UUID playerId = player.getUniqueId();
        if (!beginCommandQueueWrite()) {
            persistLateCommands(plugin, player, playerId, commands);
            return;
        }

        CommandQueue queue;
        boolean start;
        try {
            while (true) {
                queue = COMMAND_QUEUES.computeIfAbsent(playerId, ignored -> new CommandQueue(plugin, player));
                synchronized (queue) {
                    if (COMMAND_QUEUES.get(playerId) != queue) continue;

                    queue.player = player;
                    queue.commands.addAll(commands);
                    start = !commandQueuesShutdown && !PAUSED_COMMAND_QUEUES.contains(playerId)
                        && player.isOnline() && !queue.inFlight && ACTIVE_COMMAND_QUEUES.add(playerId);
                }
                break;
            }
        }
        finally {
            endCommandQueueWrite();
        }

        // Scheduling is outside ingress so a command that reloads/stops the server cannot deadlock the shutdown barrier.
        if (start) {
            scheduleNextCommand(playerId, queue, queue.generation, 0);
        }
        else if (commandQueuesShutdown || !player.isOnline()) {
            pauseCommandQueue(plugin, playerId);
        }
    }

    public static void pauseCommandQueue(@NotNull CratesPlugin plugin, @NotNull Player player) {
        pauseCommandQueue(plugin, player.getUniqueId());
    }

    public static void resumeCommandQueue(@NotNull CratesPlugin plugin, @NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!beginCommandQueueWrite()) return;

        CommandQueue queueToStart = null;
        long startGeneration = 0L;
        try {
            if (commandQueuesShutdown) return;
            if (!RESUMING_COMMAND_QUEUES.add(playerId)) return;

            try {
                List<String> persisted = readPersistedCommands(plugin, playerId);

                while (true) {
                    CommandQueue queue = COMMAND_QUEUES.computeIfAbsent(playerId, ignored -> new CommandQueue(plugin, player));
                    boolean start;
                    synchronized (queue) {
                        if (COMMAND_QUEUES.get(playerId) != queue) continue;

                        queue.player = player;
                        if (queue.commands.isEmpty() && !queue.outboxUpdatePending && !persisted.isEmpty()) {
                            queue.commands.addAll(persisted);
                        }
                        queue.persisted |= !persisted.isEmpty();
                        boolean wasPaused = PAUSED_COMMAND_QUEUES.remove(playerId);
                        if (wasPaused || !ACTIVE_COMMAND_QUEUES.contains(playerId)) {
                            queue.generation++;
                            queue.resumeAfterFlight = queue.inFlight;
                        }
                        start = !queue.commands.isEmpty() && !queue.inFlight && ACTIVE_COMMAND_QUEUES.add(playerId);
                        if (queue.commands.isEmpty() && !queue.outboxUpdatePending) {
                            COMMAND_QUEUES.remove(playerId, queue);
                        }
                    }
                    if (start) {
                        queueToStart = queue;
                        startGeneration = queue.generation;
                    }
                    else if (persisted.isEmpty()) {
                        removePersistedCommands(plugin, playerId, queue);
                    }
                    break;
                }
            }
            finally {
                RESUMING_COMMAND_QUEUES.remove(playerId);
            }
        }
        finally {
            endCommandQueueWrite();
        }

        if (queueToStart != null) {
            scheduleNextCommand(playerId, queueToStart, startGeneration, 0);
        }
    }

    public static void shutdownCommandQueues(@NotNull CratesPlugin plugin) {
        Thread shutdownThread = Thread.currentThread();
        boolean interrupted = false;
        beginCommandQueueShutdown();
        commandQueueShutdownThread = shutdownThread;
        synchronized (COMMAND_INGRESS_LOCK) {
            commandQueueIngressClosed = true;
            while (commandQueueWriterCount > 0) {
                try {
                    COMMAND_INGRESS_LOCK.wait();
                }
                catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        for (Map.Entry<UUID, CommandQueue> entry : COMMAND_QUEUES.entrySet()) {
            UUID playerId = entry.getKey();
            CommandQueue queue = entry.getValue();
            synchronized (queue) {
                PAUSED_COMMAND_QUEUES.add(playerId);
                queue.generation++;
                queue.resumeAfterFlight = false;
                queue.persisted |= !queue.commands.isEmpty();

                while (queue.inFlight && queue.dispatchThread != shutdownThread) {
                    try {
                        queue.wait();
                    }
                    catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
        }

        boolean saved = false;
        synchronized (COMMAND_OUTBOX_LOCK) {
            try {
                YamlConfiguration config = loadCommandOutbox(plugin);
                COMMAND_QUEUES.forEach((playerId, queue) -> {
                    synchronized (queue) {
                        setPersistedCommands(config, playerId, snapshotCommands(queue, queue.inFlight));
                    }
                });
                saveCommandOutbox(plugin, config);
                saved = true;
            }
            catch (IOException | InvalidConfigurationException exception) {
                plugin.error("Could not persist pending reward commands during shutdown.");
                exception.printStackTrace();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        if (saved) {
            COMMAND_QUEUES.forEach((playerId, queue) -> {
                synchronized (queue) {
                    if (queue.commands.isEmpty() && !queue.inFlight && !queue.outboxUpdatePending) {
                        COMMAND_QUEUES.remove(playerId, queue);
                    }
                }
            });
            ACTIVE_COMMAND_QUEUES.clear();
            RESUMING_COMMAND_QUEUES.clear();
        }
    }

    private static void scheduleNextCommand(@NotNull UUID playerId,
                                            @NotNull CommandQueue queue,
                                            long generation,
                                            int commandsThisTick) {
        if (!canProcess(playerId, queue, generation)) return;

        QueuedCommand command = nextCommand(queue);
        if (command == null) {
            finishCommandQueue(playerId, queue, generation);
            return;
        }

        FoliaScheduler scheduler = new FoliaScheduler(queue.plugin);
        Runnable task = () -> dispatchCommand(playerId, queue, scheduler, command, generation, commandsThisTick);
        if (!schedule(queue.plugin, scheduler, queue.player, task, command.playerCommand())) {
            pauseCommandQueue(queue.plugin, playerId);
        }
    }

    private static void dispatchCommand(@NotNull UUID playerId,
                                        @NotNull CommandQueue queue,
                                        @NotNull FoliaScheduler scheduler,
                                        @NotNull QueuedCommand command,
                                        long generation,
                                        int commandsThisTick) {
        assert commandsThisTick >= 0 && commandsThisTick < COMMANDS_PER_TICK : "Invalid command tick budget";
        synchronized (queue) {
            if (!canProcess(playerId, queue, generation) || queue.inFlight) return;
            queue.inFlight = true;
            queue.dispatchThread = Thread.currentThread();
        }

        Player player = queue.player;
        if (command.playerCommand() && !player.isOnline()) {
            synchronized (queue) {
                queue.inFlight = false;
                queue.dispatchThread = null;
                queue.notifyAll();
            }
            pauseCommandQueue(queue.plugin, playerId);
            return;
        }

        try {
            Bukkit.dispatchCommand(command.playerCommand() ? player : Bukkit.getConsoleSender(), command.value());
        }
        catch (RuntimeException exception) {
            queue.plugin.error("Could not dispatch reward command: " + command.value());
            exception.printStackTrace();
        }
        finally {
            // shortcut: crash recovery is at-least-once and may replay arbitrary commands; require idempotent receipts for exactly-once delivery.
            boolean continueCurrent;
            boolean restartAfterFlight;
            boolean updatePersisted;
            long restartGeneration;
            synchronized (queue) {
                queue.commands.pollFirst();
                queue.inFlight = false;
                queue.dispatchThread = null;
                queue.notifyAll();
                continueCurrent = canProcess(playerId, queue, generation);
                restartAfterFlight = !continueCurrent && queue.resumeAfterFlight
                    && canProcess(playerId, queue, queue.generation);
                restartGeneration = queue.generation;
                if (restartAfterFlight) {
                    queue.resumeAfterFlight = false;
                    ACTIVE_COMMAND_QUEUES.add(playerId);
                }
                updatePersisted = !continueCurrent && queue.persisted;
                queue.outboxUpdatePending = updatePersisted;
            }
            if (updatePersisted) {
                boolean updated = persistCommands(queue.plugin, playerId, queue);
                synchronized (queue) {
                    queue.outboxUpdatePending = !updated;
                    if (!updated) {
                        PAUSED_COMMAND_QUEUES.add(playerId);
                        ACTIVE_COMMAND_QUEUES.remove(playerId);
                        queue.generation++;
                        queue.resumeAfterFlight = false;
                    }
                }
                if (!updated) return;
            }
            if (restartAfterFlight) {
                scheduleNextCommand(playerId, queue, restartGeneration, 0);
                return;
            }
            if (!continueCurrent) {
                return;
            }

            int nextCount = commandsThisTick + 1;
            if (nextCount >= COMMANDS_PER_TICK) {
                scheduleNextCommand(playerId, queue, generation, 0);
            }
            else {
                QueuedCommand next = nextCommand(queue);
                if (next == null) {
                    finishCommandQueue(playerId, queue, generation);
                }
                else if (next.playerCommand() == command.playerCommand()) {
                    dispatchCommand(playerId, queue, scheduler, next, generation, nextCount);
                }
                else {
                    Runnable task = () -> dispatchCommand(playerId, queue, scheduler, next, generation, nextCount);
                    if (!schedule(queue.plugin, scheduler, queue.player, task, next.playerCommand())) {
                        pauseCommandQueue(queue.plugin, playerId);
                    }
                }
            }
        }
    }

    private static boolean schedule(@NotNull CratesPlugin plugin,
                                    @NotNull FoliaScheduler scheduler,
                                    @NotNull Player player,
                                    @NotNull Runnable task,
                                    boolean playerContext) {
        try {
            return (playerContext ? scheduler.runTask(player, task) : scheduler.runTask(task)) != null;
        }
        catch (RuntimeException exception) {
            plugin.error("Could not schedule the ordered reward command queue.");
            exception.printStackTrace();
            return false;
        }
    }

    private static void finishCommandQueue(@NotNull UUID playerId,
                                           @NotNull CommandQueue queue,
                                           long generation) {
        boolean restart = false;
        boolean removePersisted = false;
        synchronized (queue) {
            if (!canProcess(playerId, queue, generation)) return;

            ACTIVE_COMMAND_QUEUES.remove(playerId);
            if (queue.commands.isEmpty()) {
                COMMAND_QUEUES.remove(playerId, queue);
                PAUSED_COMMAND_QUEUES.remove(playerId);
                removePersisted = queue.persisted;
            }
            else if (!PAUSED_COMMAND_QUEUES.contains(playerId)) {
                restart = ACTIVE_COMMAND_QUEUES.add(playerId);
            }
        }
        if (restart) {
            scheduleNextCommand(playerId, queue, generation, 0);
        }
        if (removePersisted) {
            removePersistedCommands(queue.plugin, playerId, queue);
        }
    }

    private static void pauseCommandQueue(@NotNull CratesPlugin plugin, @NotNull UUID playerId) {
        if (!beginCommandQueueWrite()) return;

        try {
            CommandQueue queue = COMMAND_QUEUES.get(playerId);
            if (queue == null) return;

            synchronized (COMMAND_OUTBOX_LOCK) {
                synchronized (queue) {
                    PAUSED_COMMAND_QUEUES.add(playerId);
                    ACTIVE_COMMAND_QUEUES.remove(playerId);
                    queue.generation++;
                    queue.resumeAfterFlight = false;

                    List<String> commands = snapshotCommands(queue);
                    try {
                        YamlConfiguration config = loadCommandOutbox(plugin);
                        setPersistedCommands(config, playerId, commands);
                        saveCommandOutbox(plugin, config);
                        queue.persisted = !commands.isEmpty();
                    }
                    catch (IOException | InvalidConfigurationException exception) {
                        plugin.error("Could not persist pending reward commands for '" + playerId + "'.");
                        exception.printStackTrace();
                    }
                }
            }
        }
        finally {
            endCommandQueueWrite();
        }
    }

    private static boolean beginCommandQueueWrite() {
        synchronized (COMMAND_INGRESS_LOCK) {
            if (commandQueueIngressClosed) return false;

            commandQueueWriterCount++;
            return true;
        }
    }

    private static void endCommandQueueWrite() {
        synchronized (COMMAND_INGRESS_LOCK) {
            if (commandQueueWriterCount <= 0) {
                throw new IllegalStateException("Command queue writer was not registered.");
            }

            commandQueueWriterCount--;
            COMMAND_INGRESS_LOCK.notifyAll();
        }
    }

    private static boolean canProcess(@NotNull UUID playerId,
                                      @NotNull CommandQueue queue,
                                      long generation) {
        return !commandQueuesShutdown && !PAUSED_COMMAND_QUEUES.contains(playerId)
            && COMMAND_QUEUES.get(playerId) == queue && queue.generation == generation;
    }

    @NotNull
    private static List<String> snapshotCommands(@NotNull CommandQueue queue) {
        return snapshotCommands(queue, false);
    }

    @NotNull
    private static List<String> snapshotCommands(@NotNull CommandQueue queue, boolean excludeInFlight) {
        assert !queue.inFlight || !queue.commands.isEmpty() : "In-flight command is missing from its queue";
        List<String> commands = new ArrayList<>(queue.commands);
        if (excludeInFlight && !commands.isEmpty()) {
            commands.removeFirst();
        }
        return commands;
    }

    @Nullable
    private static QueuedCommand nextCommand(@NotNull CommandQueue queue) {
        synchronized (queue) {
            while (!queue.commands.isEmpty()) {
                String raw = queue.commands.peekFirst();
                if (raw == null) return null;

                String value = raw.trim();
                boolean playerCommand = value.startsWith(Players.PLAYER_COMMAND_PREFIX);
                String command = playerCommand ? value.substring(Players.PLAYER_COMMAND_PREFIX.length()).trim() : value;
                if (!command.isEmpty()) {
                    return new QueuedCommand(command, playerCommand);
                }
                queue.commands.pollFirst();
            }
            return null;
        }
    }

    private static boolean persistCommands(@NotNull CratesPlugin plugin,
                                           @NotNull UUID playerId,
                                            @NotNull CommandQueue queue) {
        synchronized (COMMAND_OUTBOX_LOCK) {
            synchronized (queue) {
                try {
                    YamlConfiguration config = loadCommandOutbox(plugin);
                    setPersistedCommands(config, playerId, snapshotCommands(queue));
                    saveCommandOutbox(plugin, config);
                    return true;
                }
                catch (IOException | InvalidConfigurationException exception) {
                    plugin.error("Could not persist pending reward commands for '" + playerId + "'.");
                    exception.printStackTrace();
                    return false;
                }
            }
        }
    }

    private static void persistLateCommands(@NotNull CratesPlugin plugin,
                                            @NotNull Player player,
                                            @NotNull UUID playerId,
                                            @NotNull List<String> commands) {
        synchronized (COMMAND_OUTBOX_LOCK) {
            while (true) {
                CommandQueue queue = COMMAND_QUEUES.computeIfAbsent(playerId, ignored -> new CommandQueue(plugin, player));
                synchronized (queue) {
                    if (COMMAND_QUEUES.get(playerId) != queue) continue;

                    queue.player = player;
                    queue.commands.addAll(commands);
                    queue.generation++;
                    queue.resumeAfterFlight = false;
                    PAUSED_COMMAND_QUEUES.add(playerId);
                    ACTIVE_COMMAND_QUEUES.remove(playerId);

                    boolean excludeCurrent = queue.inFlight && queue.dispatchThread == commandQueueShutdownThread;
                    List<String> pending = snapshotCommands(queue, excludeCurrent);
                    try {
                        YamlConfiguration config = loadCommandOutbox(plugin);
                        setPersistedCommands(config, playerId, pending);
                        saveCommandOutbox(plugin, config);
                        queue.persisted = !pending.isEmpty();
                    }
                    catch (IOException | InvalidConfigurationException exception) {
                        plugin.error("Could not persist late reward commands for '" + playerId + "'.");
                        exception.printStackTrace();
                    }
                    return;
                }
            }
        }
    }

    @NotNull
    private static List<String> readPersistedCommands(@NotNull CratesPlugin plugin, @NotNull UUID playerId) {
        synchronized (COMMAND_OUTBOX_LOCK) {
            try {
                return loadCommandOutbox(plugin).getStringList(commandPath(playerId));
            }
            catch (IOException | InvalidConfigurationException exception) {
                plugin.error("Could not load pending reward commands for '" + playerId + "'.");
                exception.printStackTrace();
                return List.of();
            }
        }
    }

    private static void removePersistedCommands(@NotNull CratesPlugin plugin,
                                                @NotNull UUID playerId,
                                                @NotNull CommandQueue expectedQueue) {
        synchronized (COMMAND_OUTBOX_LOCK) {
            synchronized (expectedQueue) {
                CommandQueue currentQueue = COMMAND_QUEUES.get(playerId);
                if (currentQueue != null && currentQueue != expectedQueue) return;
                if (currentQueue == expectedQueue && (!expectedQueue.commands.isEmpty() || expectedQueue.inFlight)) return;

                try {
                    YamlConfiguration config = loadCommandOutbox(plugin);
                    if (!config.contains(commandPath(playerId))) return;

                    config.set("Pending." + playerId, null);
                    saveCommandOutbox(plugin, config);
                }
                catch (IOException | InvalidConfigurationException exception) {
                    plugin.error("Could not remove completed reward commands for '" + playerId + "'.");
                    exception.printStackTrace();
                }
            }
        }
    }

    private static void setPersistedCommands(@NotNull YamlConfiguration config,
                                             @NotNull UUID playerId,
                                             @NotNull List<String> commands) {
        config.set(commandPath(playerId), commands.isEmpty() ? null : commands);
    }

    @NotNull
    private static String commandPath(@NotNull UUID playerId) {
        return "Pending." + playerId + ".Commands";
    }

    @NotNull
    private static YamlConfiguration loadCommandOutbox(@NotNull CratesPlugin plugin) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        File file = new File(plugin.getDataFolder(), COMMAND_OUTBOX_FILE);
        if (file.isFile()) {
            config.load(file);
        }
        return config;
    }

    private static void saveCommandOutbox(@NotNull CratesPlugin plugin,
                                          @NotNull YamlConfiguration config) throws IOException {
        Path target = new File(plugin.getDataFolder(), COMMAND_OUTBOX_FILE).toPath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "pending-commands-", ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record QueuedCommand(@NotNull String value, boolean playerCommand) {

    }

    private static final class CommandQueue {

        private final CratesPlugin plugin;
        private final Deque<String> commands;
        private Player player;
        private boolean persisted;
        private boolean inFlight;
        private boolean resumeAfterFlight;
        private boolean outboxUpdatePending;
        private volatile long generation;
        private Thread dispatchThread;

        private CommandQueue(@NotNull CratesPlugin plugin, @NotNull Player player) {
            this.plugin = plugin;
            this.player = player;
            this.commands = new ArrayDeque<>();
        }

    }

    public static void forEachPlayerInEffectRange(@NotNull CratesPlugin plugin,
                                                  @NotNull Location location,
                                                  @NotNull Consumer<Player> action) {
        plugin.runTask(location, () -> {
            World world = location.getWorld();
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;

            new ArrayList<>(world.getPlayersSeeingChunk(chunkX, chunkZ)).forEach(player -> plugin.runTask(player, () -> {
                if (isInEffectRange(player, location)) {
                    action.accept(player);
                }
            }));
        });
    }

    public static boolean isInEffectRange(@NotNull Player player, @NotNull Location location) {
        World world = location.getWorld();
        double distance = Config.CRATE_EFFECTS_VISIBILITY_DISTANCE.get();

        return distance >= 0 && player.getWorld() == world && player.getLocation().distanceSquared(location) <= distance * distance;
    }

    @NotNull
    public static ItemStack removeCrateTags(@NotNull ItemStack itemStack) {
        ItemUtil.editMeta(itemStack, meta -> {
            PDCUtil.remove(meta, Keys.crateId);
            PDCUtil.remove(meta, Keys.keyId);
        });
        return itemStack;
    }

    @NotNull
    public static ItemStack getQuestionStack() {
        return NightItem.asCustomHead("2705fd94a0c431927fb4e639b0fcfb49717e412285a02b439e0112da22b2e2ec").hideAllComponents().getItemStack();
    }

    @NotNull
    public static NightItem getDefaultLinkTool() {
        return NightItem.fromType(Material.BLAZE_ROD)
            .hideAllComponents()
            .setDisplayName(TagWrappers.GOLD.and(TagWrappers.BOLD).wrap("Link Tool"))
            .setLore(Lists.newList(
                TagWrappers.GRAY.wrap("Click a block to link it"),
                TagWrappers.GRAY.wrap("with the crate!")
            ));
    }
    @NotNull
    public static ItemStack getDefaultItem(@NotNull Crate crate) {
        return NightItem.fromType(Material.CHEST)
            .setDisplayName(crate.getName())
            .setLore(crate.getDescription())
            .hideAllComponents()
            .getItemStack();
    }

    @NotNull
    @Deprecated
    public static String createID(@NotNull String name) {
        String id = StringUtil.transformForID(name);
        if (id.isBlank()) id = UUID.randomUUID().toString().substring(0, 8);

        return id;
    }

    @NotNull
    public static String generateRewardID(@NotNull Crate crate, @NotNull ItemStack itemStack) {
        String itemName = Optional.ofNullable(ItemUtil.getDisplayNameSerialized(itemStack))
            .map(NightMessage::stripTags)
            .orElse(BukkitThing.getValue(itemStack.getType()));

        String name = Strings.varStyle(itemName).orElse(UUID.randomUUID().toString());

        int count = 0;
        while (crate.getReward(addCount(name, count)) != null) {
            count++;
        }

        return addCount(name, count);
    }

    public static boolean isValidCommand(@NotNull String command) {
        String firstPart = command.split(" ")[0];

        int index = firstPart.indexOf(':');
        String name = index >= 0 ? firstPart.substring(index + 1) : firstPart;

        return CommandUtil.getCommand(name).isPresent();
    }

    private static String addCount(@NotNull String str, int count) {
        return count <= 0 ? str : str + "_" + count;
    }

    public static boolean isSupportedParticle(@NotNull Particle particle) {
        return particle != Particle.VIBRATION && particle != Particle.DUST_COLOR_TRANSITION && particle != Particle.TRAIL;
    }

    public static boolean isSupportedParticleData(@NotNull UniParticle particle) {
        return particle.getParticle() != null && isSupportedParticleData(particle.getParticle().getDataType());
    }

    public static boolean isSupportedParticleData(@NotNull Class<?> clazz) {
        return clazz != Void.class && clazz != Vibration.class && clazz != Particle.DustTransition.class && clazz != Particle.Trail.class;
    }
}
