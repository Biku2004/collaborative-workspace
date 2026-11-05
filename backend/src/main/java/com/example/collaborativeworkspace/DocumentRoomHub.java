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
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
            task -> { Thread thread = new Thread(task, "room-sender"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private final DocumentRepository documents;
    private final WorkspaceRepository workspaces;
    public DocumentRoomHub(DocumentRepository documents, WorkspaceRepository workspaces) { this.documents = documents; this.workspaces = workspaces; }

    final class Peer {
        final WebSocketSession socket;
        final UUID workspace, user;
        final HttpSession http;
        final BoundedSocketSender sender;
        volatile UUID document;
        volatile boolean welcomed;
        volatile boolean roomReady;
        Peer(WebSocketSession socket) {
            this.socket = socket;
            workspace = UUID.fromString((String) socket.getAttributes().get("workspaceId"));
            user = UUID.fromString((String) socket.getAttributes().get("userId"));
            http = (HttpSession) socket.getAttributes().get("httpSession");
            sender = new BoundedSocketSender(socket, executor, () -> remove(socket));
        }
        boolean sessionValid() {
            try { http.getCreationTime(); return socket.isOpen(); }
            catch (IllegalStateException ex) { return false; }
        }
        boolean workspaceAllowed() { return sessionValid() && workspaces.memberRole(workspace, user).isPresent(); }
        boolean allowed(UUID id) { return sessionValid() && documents.canRead(workspace, id, user); }
        java.util.Optional<String> readableFormat(UUID id) {
            if (!sessionValid()) return java.util.Optional.empty();
            return documents.findVisible(id, user).filter(document -> workspace.equals(document.workspaceId())).map(Document::collaborationFormat);
        }
    }

    Peer add(WebSocketSession socket) {
        if (count.incrementAndGet() > MAX_CONNECTIONS) { count.decrementAndGet(); return null; }
        Peer peer = new Peer(socket);
        if (peers.putIfAbsent(socket.getId(), peer) != null) { count.decrementAndGet(); return null; }
        return peer;
    }
    Peer get(WebSocketSession socket) { return peers.get(socket.getId()); }
    void remove(WebSocketSession socket) {
        Peer peer = peers.remove(socket.getId());
        if (peer != null) { count.decrementAndGet(); peer.sender.close(CloseStatus.NORMAL); }
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
            if (document == null || !peer.roomReady) continue;
            try {
                var snapshot = updates.findAccessible(peer.workspace, document, peer.user);
                if (snapshot.isEmpty()) { peer.sender.close(CloseStatus.POLICY_VIOLATION); continue; }
                var head = snapshot.get();
                String value = json.writeValueAsString(java.util.Map.of("type", "HEAD", "version", 1, "documentId", document,
                        "epoch", head.epoch(), "sequence", head.sequence()));
                peer.sender.send(value, () -> document.equals(peer.document) && peer.allowed(document));
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Head notification failed: {}", ex.getClass().getName());
            }
        }
    }
    int count() { return count.get(); }
    @PreDestroy void shutdown() { for (Peer peer : peers.values()) peer.sender.close(CloseStatus.GOING_AWAY); executor.shutdownNow(); }
}
