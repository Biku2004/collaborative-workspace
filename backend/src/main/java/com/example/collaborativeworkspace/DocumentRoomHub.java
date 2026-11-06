package com.example.collaborativeworkspace;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@Profile("dev")
public class DocumentRoomHub {
    static final int MAX_CONNECTIONS = 256;
    static final long MAX_AUTH_CHECK_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
            task -> { Thread thread = new Thread(task, "room-sender"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private final DocumentRepository documents;
    private final WorkspaceRepository workspaces;
    private final tools.jackson.databind.json.JsonMapper json;
    private final java.util.function.LongSupplier nanoClock;
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentRoomHub(DocumentRepository documents, WorkspaceRepository workspaces, tools.jackson.databind.json.JsonMapper json) {
        this(documents, workspaces, json, System::nanoTime);
    }
    DocumentRoomHub(DocumentRepository documents, WorkspaceRepository workspaces, tools.jackson.databind.json.JsonMapper json, java.util.function.LongSupplier nanoClock) {
        this.documents = documents; this.workspaces = workspaces; this.json = json; this.nanoClock = nanoClock;
    }

    final class Peer {
        final WebSocketSession socket;
        final UUID workspace, user;
        final String userEmail;
        final UUID presenceId = UUID.randomUUID();
        final HttpSession http;
        final BoundedSocketSender sender;
        volatile UUID document;
        volatile boolean welcomed;
        volatile boolean roomReady;
        volatile boolean presenceEnabled;
        volatile boolean hasPresence;
        volatile boolean revoked;
        private long presenceWindowNanos;
        private int presenceCount;
        Peer(WebSocketSession socket) {
            this.socket = socket;
            workspace = UUID.fromString((String) socket.getAttributes().get("workspaceId"));
            user = UUID.fromString((String) socket.getAttributes().get("userId"));
            userEmail = (String) socket.getAttributes().get("userEmail");
            http = (HttpSession) socket.getAttributes().get("httpSession");
            sender = new BoundedSocketSender(socket, executor, () -> remove(socket));
        }
        boolean sessionValid() {
            try { http.getCreationTime(); return !revoked && socket.isOpen(); }
            catch (IllegalStateException ex) { return false; }
        }
        boolean workspaceAllowed() {
            long started = nanoClock.getAsLong();
            return sessionValid() && workspaces.memberRole(workspace, user).isPresent() && current(started);
        }
        boolean current(long started) {
            long elapsed = nanoClock.getAsLong() - started;
            return elapsed >= 0 && elapsed <= MAX_AUTH_CHECK_NANOS && sessionValid();
        }
        synchronized boolean allowPresence(long now) {
            if (presenceWindowNanos == 0 || now - presenceWindowNanos >= TimeUnit.SECONDS.toNanos(1)) {
                presenceWindowNanos = now; presenceCount = 0;
            }
            return ++presenceCount <= 10;
        }
        boolean allowed(UUID id) {
            long started = nanoClock.getAsLong();
            return sessionValid() && documents.canRead(workspace, id, user) && current(started);
        }
        java.util.Optional<String> readableFormat(UUID id) {
            if (!sessionValid()) return java.util.Optional.empty();
            long started = nanoClock.getAsLong();
            return documents.findVisible(id, user).filter(document -> workspace.equals(document.workspaceId()) && current(started)).map(Document::collaborationFormat);
        }
    }

    Peer add(WebSocketSession socket) {
        if (count.incrementAndGet() > MAX_CONNECTIONS) { count.decrementAndGet(); return null; }
        Peer peer = new Peer(socket);
        if (peers.putIfAbsent(socket.getId(), peer) != null) { count.decrementAndGet(); return null; }
        return peer;
    }
    Peer get(WebSocketSession socket) { return peers.get(socket.getId()); }
    /** Mark every affected peer before attempting any potentially slow transport close. */
    void invalidate(UUID workspace, UUID user) {
        var affected = peers.values().stream().filter(peer -> workspace.equals(peer.workspace) && user.equals(peer.user)).toList();
        for (Peer peer : affected) peer.revoked = true;
        for (Peer peer : affected) {
            try { peer.sender.close(CloseStatus.POLICY_VIOLATION); }
            catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Access invalidation close failed: {}", ex.getClass().getName()); }
        }
    }
    void remove(WebSocketSession socket) {
        Peer peer = peers.remove(socket.getId());
        if (peer != null) { count.decrementAndGet(); clearPresence(peer); peer.sender.close(CloseStatus.NORMAL); }
    }
    void clearPresence(Peer peer) {
        UUID document = peer.document;
        if (document == null || !peer.roomReady || !peer.hasPresence) return;
        peer.hasPresence = false;
        String value = json.writeValueAsString(java.util.Map.of("type", "PRESENCE_LEFT", "version", 1,
                "documentId", document, "presenceId", peer.presenceId));
        broadcastPresence(peer, document, value);
    }
    void broadcastPresence(Peer sender, UUID document, String value) {
        for (Peer target : peers.values()) {
            if (target != sender && target.roomReady && target.presenceEnabled && sender.workspace.equals(target.workspace) && document.equals(target.document))
                target.sender.sendEphemeral(value, () -> document.equals(target.document) && target.allowed(document));
        }
    }
    void broadcast(Peer sender, UUID document, String json) {
        for (Peer target : peers.values()) {
            if (target.roomReady && sender.workspace.equals(target.workspace) && document.equals(target.document)) {
                target.sender.send(json, () -> document.equals(sender.document) && document.equals(target.document)
                        && sender.allowed(document) && target.allowed(document));
            }
        }
    }
    void broadcastCommitted(UUID workspace, UUID document, String json) {
        for (Peer target : peers.values()) {
            if (target.roomReady && workspace.equals(target.workspace) && document.equals(target.document))
                target.sender.send(json, () -> document.equals(target.document) && target.allowed(document));
        }
    }
    void notifyHeads(DocumentUpdateRepository updates, tools.jackson.databind.json.JsonMapper json) {
        for (Peer peer : peers.values()) {
            UUID document = peer.document;
            try {
                if (!peer.sessionValid()) { peer.sender.close(CloseStatus.POLICY_VIOLATION); continue; }
                if (document == null || !peer.roomReady) {
                    if (!peer.workspaceAllowed()) peer.sender.close(CloseStatus.POLICY_VIOLATION);
                    continue;
                }
                long started = nanoClock.getAsLong();
                var snapshot = updates.findAccessible(peer.workspace, document, peer.user);
                if (snapshot.isEmpty() || !peer.current(started)) { peer.sender.close(CloseStatus.POLICY_VIOLATION); continue; }
                var head = snapshot.get();
                String value = json.writeValueAsString(java.util.Map.of("type", "HEAD", "version", 1, "documentId", document,
                        "epoch", head.epoch(), "sequence", head.sequence()));
                peer.sender.send(value, () -> document.equals(peer.document) && peer.allowed(document));
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Head notification failed: {}", ex.getClass().getName());
                peer.revoked = true;
                peer.sender.close(CloseStatus.POLICY_VIOLATION);
            }
        }
    }
    int count() { return count.get(); }
    @PreDestroy void shutdown() { for (Peer peer : peers.values()) peer.sender.close(CloseStatus.GOING_AWAY); executor.shutdownNow(); }
}
