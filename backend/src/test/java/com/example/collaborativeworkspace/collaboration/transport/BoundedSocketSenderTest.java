package com.example.collaborativeworkspace.collaboration.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class BoundedSocketSenderTest {
    @Test void disposablePresenceIsBoundedAndEvictedForDurableMessages() throws Exception {
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var sender = new BoundedSocketSender(socket, executor, () -> {});
            sender.send("first", () -> true); assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) sender.sendEphemeral("presence", () -> true);
            assertEquals(4, sender.queuedMessages());
            for (int i = 0; i < 13; i++) sender.send("durable", () -> true);
            assertEquals(13, sender.queuedMessages());
            verify(socket, never()).close(any());
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void rejectedExecutorDropsDisposablePresenceWithoutClosing() throws Exception {
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var sender = new BoundedSocketSender(socket, task -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> {});
        sender.sendEphemeral("presence", () -> true);
        assertEquals(0, sender.queuedMessages());
        verify(socket, never()).close(any());
    }
    @Test void slowSocketCannotAccumulateAnUnboundedQueue() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; }).when(socket).sendMessage(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var sender = new BoundedSocketSender(socket, executor, () -> {});
            sender.send("first", () -> true);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < BoundedSocketSender.MAX_MESSAGES; i++) sender.send("queued", () -> true);
            assertEquals(BoundedSocketSender.MAX_MESSAGES, sender.queuedMessages());
            assertTrue(sender.queuedBytes() <= BoundedSocketSender.MAX_BYTES);
            sender.send("overflow", () -> true);
            assertEquals(0, sender.queuedMessages());
            assertEquals(0, sender.queuedBytes());
            verify(socket).close(any());
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void fullSharedExecutorClosesAndClearsTheSocketQueue() throws Exception {
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var sender = new BoundedSocketSender(socket, task -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> {},
                new com.example.collaborativeworkspace.observability.WorkspaceTelemetry(metrics));
        sender.send("test", () -> true);
        assertEquals(0, sender.queuedMessages());
        verify(socket).close(any());
        assertEquals(1, metrics.get("workspace.transport").tag("signal", "executor_rejected").counter().count());
        assertEquals(1, metrics.get("workspace.transport").tag("signal", "socket_closed").counter().count());
    }

    @Test void byteLimitAlsoClosesBeforeMessageCountLimit() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var sender = new BoundedSocketSender(socket, executor, () -> {});
            sender.send("first", () -> true); assertTrue(started.await(5, TimeUnit.SECONDS));
            sender.send("x".repeat(9000), () -> true);
            assertEquals(9000, sender.queuedBytes());
            sender.send("y".repeat(9000), () -> true);
            assertEquals(0, sender.queuedBytes());
            verify(socket).close(any());
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }
}
