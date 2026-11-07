package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebhookDeliveryTest {
    private static final class Deleted extends RuntimeException { }
    private static final class Fake implements WebhookDelivery.Transport<Integer, String> {
        int acquisitions;
        int failures;
        RuntimeException failure = new Deleted();
        List<String> sent = new ArrayList<>();
        CompletableFuture<Long> pending;

        public CompletableFuture<Integer> acquire(long channel) {
            return CompletableFuture.completedFuture(++acquisitions);
        }
        public CompletableFuture<Long> send(Integer webhook, String payload) {
            sent.add(webhook + ":" + payload);
            if (failures-- > 0) return CompletableFuture.failedFuture(failure);
            if (pending != null) {
                var result = pending;
                pending = null;
                return result;
            }
            return CompletableFuture.completedFuture(42L);
        }
        public boolean isUnknownWebhook(Throwable error) {
            while (error instanceof CompletionException) error = error.getCause();
            return error instanceof Deleted;
        }
    }

    @Test void cachesAndOrdersMessagesWhileFirstSendIsPending() {
        Fake transport = new Fake();
        CompletableFuture<Long> pending = new CompletableFuture<>();
        transport.pending = pending;
        var delivery = new WebhookDelivery<>(transport);
        var first = delivery.enqueue(1, () -> CompletableFuture.completedFuture("Steve"));
        AtomicInteger resolved = new AtomicInteger();
        var second = delivery.enqueue(1, () -> {
            resolved.incrementAndGet();
            return CompletableFuture.completedFuture("Alex");
        });
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(0, resolved.get());
        assertEquals(List.of("1:Steve"), transport.sent);
        pending.complete(41L);
        assertEquals(41L, first.join());
        assertEquals(42L, second.join());
        assertEquals(1, transport.acquisitions);
        assertEquals(List.of("1:Steve", "1:Alex"), transport.sent);
    }

    @Test void unknownWebhookReacquiresAndRetriesSamePayloadOnce() {
        Fake transport = new Fake();
        transport.failures = 1;
        var delivery = new WebhookDelivery<>(transport);
        assertEquals(42L, delivery.enqueue(1, () -> CompletableFuture.completedFuture("message")).join());
        delivery.enqueue(1, () -> CompletableFuture.completedFuture("next")).join();
        assertEquals(2, transport.acquisitions);
        assertEquals(List.of("1:message", "2:message", "2:next"), transport.sent);
    }

    @Test void exhaustedRetryDoesNotPoisonQueueOrKeepDeletedWebhook() {
        Fake transport = new Fake();
        transport.failures = 2;
        var delivery = new WebhookDelivery<>(transport);
        assertThrows(CompletionException.class, () -> delivery.enqueue(1, () -> CompletableFuture.completedFuture("fails")).join());
        assertEquals(2, transport.acquisitions);
        delivery.enqueue(1, () -> CompletableFuture.completedFuture("recovers")).join();
        assertEquals(3, transport.acquisitions);
    }

    @Test void otherErrorsAreNotRetriedAndChannelsHaveIndependentCaches() {
        Fake transport = new Fake();
        transport.failure = new IllegalStateException("permission denied");
        transport.failures = 1;
        var delivery = new WebhookDelivery<>(transport);
        assertThrows(CompletionException.class, () -> delivery.enqueue(1, () -> CompletableFuture.completedFuture("fails")).join());
        delivery.enqueue(1, () -> CompletableFuture.completedFuture("next")).join();
        assertEquals(1, transport.acquisitions);
        delivery.enqueue(2, () -> CompletableFuture.completedFuture("other channel")).join();
        assertEquals(2, transport.acquisitions);
    }

    @Test void identityResolutionFailureDoesNotPreventNextMessage() {
        Fake transport = new Fake();
        var delivery = new WebhookDelivery<>(transport);
        assertThrows(CompletionException.class, () -> delivery.enqueue(1, () -> { throw new IllegalArgumentException(); }).join());
        assertEquals(42L, delivery.enqueue(1, () -> CompletableFuture.completedFuture("ok")).join());
    }
}
