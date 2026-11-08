package de.erdbeerbaerlp.dcintegration.architectury.topic;

import com.google.gson.Gson;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public final class ChannelTopicUpdater {
    private static volatile ChannelTopicUpdater active;
    private final MinecraftServer server;
    private final DiscordIntegration discord;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "DiscordIntegration Channel Topics"); thread.setDaemon(true); return thread;
    });
    private final TopicSettings.Store settings = new TopicSettings.Store();
    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final Path ledger;
    private final long started = System.nanoTime();
    private long lastUpdate;
    private volatile boolean stopping;
    private final TopicDelivery delivery;

    private ChannelTopicUpdater(MinecraftServer server, DiscordIntegration discord) {
        this.server = server;
        this.discord = discord;
        ledger = server.getWorldPath(LevelResource.ROOT).resolve("dcintegration-topic-players.json");
        delivery = new TopicDelivery((id, topic) -> {
            var channel = discord.getChannel(id);
            if (channel instanceof TextChannel text) {
                if (topic.equals(text.getTopic())) return CompletableFuture.completedFuture(null);
                return text.getManager().setTopic(topic).submit().thenApply(ignored -> null);
            }
            if (channel instanceof NewsChannel news) {
                if (topic.equals(news.getTopic())) return CompletableFuture.completedFuture(null);
                return news.getManager().setTopic(topic).submit().thenApply(ignored -> null);
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Topic target must be a text or announcement channel"));
        });
        worker.execute(this::loadPlayers);
        worker.scheduleWithFixedDelay(this::pulse, 0, 30, TimeUnit.SECONDS);
    }

    public static void start(MinecraftServer server, DiscordIntegration discord) {
        if (active == null && discord != null) active = new ChannelTopicUpdater(server, discord);
    }
    public static void joined(UUID uuid) {
        var updater = active;
        if (updater != null && !updater.stopping && updater.players.add(uuid)) {
            try { updater.worker.execute(updater::savePlayers); } catch (RejectedExecutionException ignored) { }
        }
    }
    public static void shutdown() {
        var updater = active;
        if (updater == null) return;
        updater.stopping = true;
        Map<String, String> snapshot = updater.snapshot(); // Called on the server thread before JDA shuts down.
        try {
            updater.worker.submit(() -> {
                updater.savePlayers();
                return updater.publish(snapshot, true);
            }).get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            DiscordIntegration.LOGGER.error("Could not finish shutdown channel topics: " + error.getClass().getSimpleName());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { updater.worker.shutdownNow(); active = null; }
    }

    private void pulse() {
        try {
            if (stopping || discord.getJDA() == null || discord.getJDA().getStatus() != net.dv8tion.jda.api.JDA.Status.CONNECTED) return;
            var config = settings.get();
            long now = System.nanoTime();
            if (lastUpdate != 0 && now - lastUpdate < TimeUnit.MINUTES.toNanos(config.intervalMinutes())) return;
            lastUpdate = now;
            server.execute(() -> {
                if (stopping) return;
                Map<String, String> snapshot = snapshot();
                try { worker.execute(() -> { if (!stopping) publish(snapshot, false); }); }
                catch (RejectedExecutionException ignored) { }
            });
        } catch (Exception error) { DiscordIntegration.LOGGER.error("Channel topic update failed: " + error.getClass().getSimpleName()); }
    }

    private CompletableFuture<Void> publish(Map<String, String> snapshot, boolean offline) {
        var config = settings.get();
        if (offline && !config.ChannelTopicUpdaterChannelTopicsAtShutdownEnabled) return CompletableFuture.completedFuture(null);
        if (discord.getJDA() == null) return CompletableFuture.completedFuture(null);
        snapshot.put("totalplayers", Integer.toString(players.size()));
        String chat = Configuration.instance().advanced.chatOutputChannelID;
        String log = Configuration.instance().commandLog.channelID;
        var chatChannel = discord.getChannel(chat);
        String resolvedChat = chatChannel == null ? chat : chatChannel.getId();
        var result = send(resolvedChat, offline ? config.ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat : config.ChannelTopicUpdaterChatChannelTopicFormat, snapshot, offline);
        // A shared channel has one topic; the chat format wins.
        var logChannel = log.equals("0") ? null : discord.getChannel(log);
        String resolvedLog = logChannel == null ? log : logChannel.getId();
        if (!log.equals("0") && !resolvedLog.equals(resolvedChat)) {
            var command = send(resolvedLog, offline ? config.ChannelTopicUpdaterCommandLogChannelTopicAtServerShutdownFormat : config.ChannelTopicUpdaterCommandLogChannelTopicFormat, snapshot, offline);
            result = CompletableFuture.allOf(result, command);
        }
        return result;
    }
    private CompletableFuture<Void> send(String channel, String format, Map<String, String> snapshot, boolean offline) {
        return delivery.send(channel, TopicFormat.render(format, snapshot, offline)).exceptionally(error -> {
            DiscordIntegration.LOGGER.error("Cannot update topic for channel " + channel + "; check channel type and Manage Channels permission.");
            return null;
        });
    }
    private Map<String, String> snapshot() {
        server.getPlayerList().getPlayers().forEach(player -> players.add(player.getUUID()));
        Map<String, String> values = new HashMap<>();
        values.put("playercount", Integer.toString(server.getPlayerCount()));
        values.put("playermax", Integer.toString(server.getPlayerList().getMaxPlayers()));
        values.put("serverversion", server.getServerVersion()); values.put("motd", server.getMotd());
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        values.put("uptimemins", Long.toString(seconds / 60)); values.put("uptimehours", Long.toString(seconds / 3600));
        ZonedDateTime now = ZonedDateTime.now();
        values.put("date", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
        values.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss z")));
        double millis = server.getAverageTickTimeNanos() / 1_000_000.0;
        values.put("tps", decimal(Math.min(20, millis <= 0 ? 20 : 1000 / millis)));
        Runtime runtime = Runtime.getRuntime();
        memory(values, "usedmemory", runtime.totalMemory() - runtime.freeMemory());
        memory(values, "freememory", runtime.freeMemory()); memory(values, "totalmemory", runtime.totalMemory()); memory(values, "maxmemory", runtime.maxMemory());
        return values;
    }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static void memory(Map<String, String> values, String key, long bytes) {
        values.put(key, Long.toString(bytes / 1024 / 1024)); values.put(key + "gb", decimal(bytes / (1024.0 * 1024 * 1024)));
    }
    private void loadPlayers() {
        try {
            if (Files.exists(ledger)) {
                try {
                    String[] stored = new Gson().fromJson(Files.readString(ledger), String[].class);
                    if (stored != null) for (String uuid : stored) addUuid(uuid);
                } catch (Exception error) {
                    DiscordIntegration.LOGGER.error("Cannot read topic player ledger; rebuilding from world player data.");
                }
            }
            for (LevelResource resource : List.of(LevelResource.PLAYER_DATA_DIR, LevelResource.PLAYER_STATS_DIR)) {
                Path directory = server.getWorldPath(resource);
                if (Files.isDirectory(directory)) try (Stream<Path> files = Files.list(directory)) {
                    files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".dat") || name.endsWith(".json"))
                            .forEach(name -> addUuid(name.substring(0, name.lastIndexOf('.'))));
                }
            }
            savePlayers();
        } catch (Exception error) { DiscordIntegration.LOGGER.error("Cannot load topic player history: " + error.getClass().getSimpleName()); }
    }
    private void addUuid(String value) { try { players.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { } }
    private void savePlayers() {
        try {
            Path temp = ledger.resolveSibling(ledger.getFileName() + ".tmp");
            Files.writeString(temp, new Gson().toJson(players.stream().map(UUID::toString).sorted().toList()));
            Files.move(temp, ledger, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { DiscordIntegration.LOGGER.error("Cannot save topic player history: " + error.getClass().getSimpleName()); }
    }
}
