package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Serializes delivery per parent channel, including acquisition and one deleted-webhook retry. */
public final class WebhookDelivery<W, P> {
    public interface Transport<W, P> {
        CompletableFuture<W> acquire(long channel);
        CompletableFuture<Long> send(W webhook, P payload);
        boolean isUnknownWebhook(Throwable error);
    }

    private final Transport<W, P> transport;
    private final ConcurrentHashMap<Long, ChannelState> channels = new ConcurrentHashMap<>();

    public WebhookDelivery(Transport<W, P> transport) {
        this.transport = transport;
    }

    public CompletableFuture<Long> enqueue(long channel, Supplier<CompletableFuture<P>> payload) {
        ChannelState state = channels.computeIfAbsent(channel, ignored -> new ChannelState());
        synchronized (state) {
            CompletableFuture<Long> result = state.tail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> payload.get())
                    .thenCompose(data -> deliver(channel, state, data, true));
            state.tail = result.handle((ignored, error) -> null);
            return result;
        }
    }

    private CompletableFuture<Long> deliver(long channel, ChannelState state, P payload, boolean retry) {
        CompletableFuture<W> webhook = state.webhook == null
                ? transport.acquire(channel).thenApply(value -> state.webhook = value)
                : CompletableFuture.completedFuture(state.webhook);
        return webhook.thenCompose(value -> transport.send(value, payload))
                .exceptionallyCompose(error -> {
                    if (retry && transport.isUnknownWebhook(error)) {
                        state.webhook = null;
                        return deliver(channel, state, payload, false);
                    }
                    // A failed retry must not leave the invalid webhook cached for subsequent messages.
                    if (transport.isUnknownWebhook(error)) state.webhook = null;
                    return CompletableFuture.failedFuture(error);
                });
    }

    private final class ChannelState {
        private W webhook;
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    }
}
