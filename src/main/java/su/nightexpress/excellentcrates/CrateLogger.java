package su.nightexpress.excellentcrates;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.nightcore.util.text.NightMessage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrateLogger {

    private static final int QUEUE_CAPACITY = 4_096;
    private static final int BATCH_SIZE = 256;

    private final CratesPlugin plugin;
    private final DateTimeFormatter formatter;
    private final File logFile;
    private final BlockingQueue<String> fileQueue;
    private final Object lifecycleLock;
    private final AtomicBoolean dropWarningLogged;
    private final Thread writerThread;

    private volatile boolean accepting;
    private volatile boolean writerFailed;

    public CrateLogger(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
        this.formatter = DateTimeFormatter.ofPattern(Config.LOGS_DATE_FORMAT.get());
        this.logFile = new File(plugin.getDataFolder(), Config.FILE_LOGS);
        this.fileQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.lifecycleLock = new Object();
        this.dropWarningLogged = new AtomicBoolean();

        if (Config.LOGS_TO_FILE.get()) {
            this.accepting = true;
            this.writerThread = new Thread(this::writeQueuedLogs, plugin.getName() + "-CrateLogger");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        }
        else {
            this.writerThread = null;
        }
    }

    public void logReward(@NotNull Player player, @NotNull Reward reward) {
        Crate crate = reward.getCrate();

        String text = player.getName() + " won " + reward.getName() +
            " (ID: " + reward.getId() + ", Weight: " + reward.getWeight() + ")" +
            " from " + crate.getName() + " (ID: " + crate.getId() + ")";

        this.log(text);
    }

    private void log(@NotNull String text) {
        if (!Config.LOGS_TO_CONSOLE.get() && !Config.LOGS_TO_FILE.get()) return;

        text = NightMessage.stripTags(text);

        if (Config.LOGS_TO_CONSOLE.get()) {
            this.plugin.info(text);
        }
        if (Config.LOGS_TO_FILE.get()) {
            String date = LocalDateTime.now().format(this.formatter);
            this.queueFileLine("[" + date + "] " + text);
        }
    }

    private void queueFileLine(@NotNull String line) {
        synchronized (this.lifecycleLock) {
            if (!this.accepting || this.writerFailed || !this.fileQueue.offer(line)) {
                // shortcut: logs may be dropped on overflow/disk failure; use an external logger if lossless audit is required.
                if (this.dropWarningLogged.compareAndSet(false, true)) {
                    this.plugin.warn("Crate reward file logs are being dropped because the async writer is unavailable or full.");
                }
            }
        }
    }

    private void writeQueuedLogs() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(this.logFile, true))) {
            while (this.accepting || !this.fileQueue.isEmpty()) {
                String first;
                try {
                    first = this.fileQueue.poll(250L, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException exception) {
                    continue;
                }
                if (first == null) continue;

                batch.add(first);
                this.fileQueue.drainTo(batch, BATCH_SIZE - 1);

                for (String line : batch) {
                    writer.append(line);
                    writer.newLine();
                }
                writer.flush();
                batch.clear();
            }
        }
        catch (IOException exception) {
            this.writerFailed = true;
            this.fileQueue.clear();
            this.plugin.error("Could not write crate reward log file: " + exception.getMessage());
        }
    }

    public void shutdown() {
        if (this.writerThread == null) return;

        synchronized (this.lifecycleLock) {
            this.accepting = false;
            this.writerThread.interrupt();

            boolean interrupted = false;
            while (this.writerThread.isAlive()) {
                try {
                    this.writerThread.join();
                }
                catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
