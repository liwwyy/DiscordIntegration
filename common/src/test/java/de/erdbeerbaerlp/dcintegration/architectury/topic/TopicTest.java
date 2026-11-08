package de.erdbeerbaerlp.dcintegration.architectury.topic;

import org.junit.jupiter.api.Test;
import dcshadow.com.moandjiezana.toml.Toml;
import dcshadow.com.moandjiezana.toml.TomlWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TopicTest {
    @Test void suppliedFormatsRenderAllLiveValuesAndQuoteReplacementCharacters() {
        var config = new TopicSettings();
        var values = Map.of("playercount", "2", "playermax", "20", "totalplayers", "42", "uptimemins", "15", "date", "2026-11-08",
                "tps", "20.00", "usedmemorygb", "1.25", "freememorygb", "0.75", "maxmemorygb", "4.00", "serverversion", "26.3 $1");
        assertEquals("2/20 players online | 42 unique players ever joined | Server online for 15 minutes | Last update: 2026-11-08",
                TopicFormat.render(config.ChannelTopicUpdaterChatChannelTopicFormat, values, false));
        assertEquals("TPS: 20.00 | Mem: 1.25GB used/0.75GB free/4.00GB max | 26.3 $1",
                TopicFormat.render(config.ChannelTopicUpdaterCommandLogChannelTopicFormat, values, false));
    }
    @Test void shutdownOnlyExpandsDocumentedPlaceholders() {
        assertEquals("42 / 26.3 / today / now / %tps%", TopicFormat.render("%totalplayers% / %serverversion% / %date% / %time% / %tps%",
                Map.of("totalplayers", "42", "serverversion", "26.3", "date", "today", "time", "now", "tps", "20"), true));
    }
    @Test void lengthLimitKeepsEmojiIntactAndEmptyFormatsDisableUpdates() {
        String result = TopicFormat.render("a".repeat(1023) + "😀", Map.of(), false);
        assertEquals(1023, result.length());
        AtomicInteger sends = new AtomicInteger();
        var delivery = new TopicDelivery((channel, topic) -> { sends.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        delivery.send("1", " ").join();
        assertEquals(0, sends.get());
    }
    @Test void rateClampsToDiscordSrvMinimumAndConfigRoundTrips() {
        var config = new TopicSettings();
        assertEquals(10, config.intervalMinutes());
        config.ChannelTopicUpdaterRateInMinutes = 15;
        config.ChannelTopicUpdaterChannelTopicsAtShutdownEnabled = false;
        var read = new Toml().read(new TomlWriter().write(config)).to(TopicSettings.class);
        read.validate();
        assertEquals(15, read.intervalMinutes());
        assertFalse(read.ChannelTopicUpdaterChannelTopicsAtShutdownEnabled);
    }
    @Test void pendingLiveWriteCompletesBeforeOfflineAndDuplicateWritesAreSkipped() {
        List<String> sends = new ArrayList<>();
        var pending = new CompletableFuture<Void>();
        var delivery = new TopicDelivery((channel, topic) -> {
            sends.add(channel + ":" + topic);
            return topic.equals("live") ? pending : CompletableFuture.completedFuture(null);
        });
        delivery.send("1", "live");
        var offline = delivery.send("1", "offline");
        assertEquals(List.of("1:live"), sends);
        assertFalse(offline.isDone());
        pending.complete(null);
        offline.join();
        delivery.send("1", "offline").join();
        delivery.send("2", "offline").join();
        assertEquals(List.of("1:live", "1:offline", "2:offline"), sends);
    }
    @Test void failedWriteIsRetriedAndDoesNotBlockShutdown() {
        AtomicInteger attempts = new AtomicInteger();
        var delivery = new TopicDelivery((channel, topic) -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException()) : CompletableFuture.completedFuture(null));
        assertThrows(CompletionException.class, () -> delivery.send("1", "offline").join());
        delivery.send("1", "offline").join();
        assertEquals(2, attempts.get());
    }
}
