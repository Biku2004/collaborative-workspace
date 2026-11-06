package com.example.collaborativeworkspace.collaboration.transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** A single ordered sender per socket. Limits include queued messages, plus at most one in-flight message. */
final class BoundedSocketSender {
    static final int MAX_MESSAGES = 16, MAX_BYTES = 16384;
    private record Pending(TextMessage message, BooleanSupplier allowed, boolean ephemeral) {}
    private final WebSocketSession socket;
    private final Executor executor;
    private final Runnable cleanup;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private int bytes;
    private boolean running, closed;

    BoundedSocketSender(WebSocketSession socket, Executor executor, Runnable cleanup) {
        this.socket = socket; this.executor = executor; this.cleanup = cleanup;
    }

    void send(String value, BooleanSupplier allowed) {
        enqueue(value, allowed, false);
    }

    /** Presence is disposable: a slow peer must not lose its durable-edit connection for it. */
    void sendEphemeral(String value, BooleanSupplier allowed) {
        enqueue(value, allowed, true);
    }

    private void enqueue(String value, BooleanSupplier allowed, boolean ephemeral) {
        TextMessage message = new TextMessage(value);
        boolean fail = false;
        synchronized (this) {
            if (closed) return;
            if (!ephemeral && (queue.size() >= MAX_MESSAGES || bytes + message.getPayloadLength() > MAX_BYTES)) {
                var iterator = queue.iterator();
                while (iterator.hasNext()) {
                    var item = iterator.next();
                    if (item.ephemeral()) { bytes -= item.message().getPayloadLength(); iterator.remove(); }
                }
            }
            if (ephemeral && queue.stream().filter(Pending::ephemeral).count() >= 4) return;
            if (queue.size() >= MAX_MESSAGES || bytes + message.getPayloadLength() > MAX_BYTES) {
                if (ephemeral) return;
                fail = true;
            }
            else {
                queue.addLast(new Pending(message, allowed, ephemeral)); bytes += message.getPayloadLength();
                if (!running) {
                    running = true;
                    try { executor.execute(this::drain); }
                    catch (RejectedExecutionException ex) {
                        if (ephemeral) { queue.removeLast(); bytes -= message.getPayloadLength(); running = false; }
                        else fail = true;
                    }
                }
            }
        }
        if (fail) close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    private void drain() {
        try {
            while (true) {
                Pending item;
                synchronized (this) {
                    if (closed) return;
                    item = queue.pollFirst();
                    if (item == null) { running = false; return; }
                    bytes -= item.message().getPayloadLength();
                }
                if (!item.allowed().getAsBoolean() || !socket.isOpen()) { close(CloseStatus.POLICY_VIOLATION); return; }
                socket.sendMessage(item.message());
            }
        } catch (Exception ex) { close(CloseStatus.SERVER_ERROR); }
    }

    void close(CloseStatus status) {
        synchronized (this) { if (closed) return; closed = true; queue.clear(); bytes = 0; }
        cleanup.run();
        try { if (socket.isOpen()) socket.close(status); } catch (IOException ignored) { }
    }

    synchronized int queuedMessages() { return queue.size(); }
    synchronized int queuedBytes() { return bytes; }
}
