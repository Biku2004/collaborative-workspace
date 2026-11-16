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
    @Test void byteBudgetCountsUtf8RatherThanJavaCharacters() throws Exception {
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var tasks = new java.util.ArrayDeque<Runnable>();
        var sender = new BoundedSocketSender(socket, tasks::addLast, () -> {});
        sender.send("\uD83D\uDE42".repeat(4096), () -> true);
        assertEquals(BoundedSocketSender.MAX_BYTES, sender.queuedBytes());
        assertEquals(1, sender.queuedMessages());
        sender.send("x", () -> true);
        assertEquals(0, sender.queuedBytes());
        verify(socket).close(org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
    }
    @Test void rejectedPresenceOnlyContinuationKeepsDurableConnectionUsable() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var sender = new BoundedSocketSender(socket, task -> {
            if (submissions.getAndIncrement() == 1) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(task);
        }, () -> {});
        for (int i = 0; i < 4; i++) sender.send("data", () -> true);
        for (int i = 0; i < 4; i++) sender.sendEphemeral("presence", () -> true);
        tasks.removeFirst().run();
        assertEquals(0, sender.queuedMessages()); assertEquals(0, sender.queuedBytes());
        sender.send("later", () -> true); tasks.removeFirst().run();
        verify(socket, times(5)).sendMessage(any()); verify(socket, never()).close(any());
    }
    @Test void busySocketsYieldToAnotherRoomAndPreservePerSocketOrder() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var order = new java.util.ArrayList<String>();
        var hot = mock(WebSocketSession.class); var cold = mock(WebSocketSession.class);
        when(hot.isOpen()).thenReturn(true); when(cold.isOpen()).thenReturn(true);
        doAnswer(call -> { order.add(((org.springframework.web.socket.TextMessage) call.getArgument(0)).getPayload()); return null; }).when(hot).sendMessage(any());
        doAnswer(call -> { order.add("cold"); return null; }).when(cold).sendMessage(any());
        var a = new BoundedSocketSender(hot, tasks::addLast, () -> {});
        var b = new BoundedSocketSender(cold, tasks::addLast, () -> {});
        for (int i = 0; i < 12; i++) a.send("hot-" + i, () -> true);
        b.send("probe", () -> true);
        assertEquals(2, tasks.size());
        tasks.removeFirst().run();
        assertEquals(BoundedSocketSender.MAX_DRAIN_BATCH, order.size());
        tasks.removeFirst().run();
        assertEquals("cold", order.get(BoundedSocketSender.MAX_DRAIN_BATCH));
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        assertEquals(java.util.stream.IntStream.range(0, 12).mapToObj(i -> "hot-" + i).toList(), order.stream().filter(value -> !value.equals("cold")).toList());
        assertEquals(0, a.queuedMessages()); verify(hot, never()).close(any()); verify(cold, never()).close(any());
    }
    @Test void rejectedContinuationClearsDurableQueueAndClosesOnlyOnce() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var cleanup = new java.util.concurrent.atomic.AtomicInteger();
        var sender = new BoundedSocketSender(socket, task -> {
            if (submissions.getAndIncrement() == 0) tasks.add(task); else throw new java.util.concurrent.RejectedExecutionException();
        }, cleanup::incrementAndGet);
        for (int i = 0; i < 8; i++) sender.send("data", () -> true);
        tasks.removeFirst().run();
        assertEquals(0, sender.queuedMessages()); assertEquals(0, sender.queuedBytes());
        sender.close(org.springframework.web.socket.CloseStatus.NORMAL);
        assertEquals(1, cleanup.get()); verify(socket).close(org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
    }
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
