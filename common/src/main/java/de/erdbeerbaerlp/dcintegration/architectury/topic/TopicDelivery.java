package de.erdbeerbaerlp.dcintegration.architectury.topic;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/** Serializes REST writes so a pending live update cannot overwrite a later offline topic. */
public final class TopicDelivery {
    private final BiFunction<String, String, CompletableFuture<Void>> transport;
    private final Map<String, String> delivered = new HashMap<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    public TopicDelivery(BiFunction<String, String, CompletableFuture<Void>> transport) { this.transport = transport; }
    public synchronized CompletableFuture<Void> send(String channel, String topic) {
        if (topic.isBlank()) return tail;
        tail = tail.handle((ignored, error) -> null).thenCompose(ignored -> {
            if (topic.equals(delivered.get(channel))) return CompletableFuture.completedFuture(null);
            return transport.apply(channel, topic).thenRun(() -> delivered.put(channel, topic));
        });
        return tail;
    }
}
