package com.example.collaborativeworkspace;

import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("dev")
public class WorkspaceSocketHandler extends AbstractWebSocketHandler {
    private final SocketSessionRegistry registry;
    private final JsonMapper json;
    private final DocumentRoomHub rooms;
    private final DocumentUpdateService updates;
    private final DocumentDeliveryService delivery;
    private final CollaborationFormatPolicy formats;
    public WorkspaceSocketHandler(SocketSessionRegistry registry, JsonMapper json, DocumentRoomHub rooms, DocumentUpdateService updates, DocumentDeliveryService delivery, CollaborationFormatPolicy formats) {
        this.registry = registry; this.json = json; this.rooms = rooms; this.updates = updates; this.delivery = delivery; this.formats = formats;
    }

    @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        socket.setTextMessageSizeLimit(16384);
        socket.setBinaryMessageSizeLimit(1024);
        var http = (jakarta.servlet.http.HttpSession) socket.getAttributes().get("httpSession");
        if (rooms.add(socket) == null) { socket.close(CloseStatus.SERVICE_OVERLOAD); return; }
        if (!registry.register(http, socket)) { rooms.remove(socket); socket.close(CloseStatus.POLICY_VIOLATION); }
    }

    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        var peer = rooms.get(socket);
        if (peer == null || message.getPayloadLength() > 16384) {
            socket.close(CloseStatus.POLICY_VIOLATION); return;
        }
        try {
            var root = json.readTree(message.getPayload());
            if (!root.isObject() || !root.path("type").isTextual() ||
                    !root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt() || root.path("version").intValue() != 1) {
                socket.close(CloseStatus.POLICY_VIOLATION); return;
            }
            String type = root.path("type").asText();
            if (!peer.welcomed) {
                if (!type.equals("HELLO") || root.size() != 2 || !peer.workspaceAllowed()) { peer.sender.close(CloseStatus.POLICY_VIOLATION); return; }
                peer.welcomed = true;
                peer.sender.send(json.writeValueAsString(Map.of("type", "WELCOME", "version", 1, "workspaceId", peer.workspace, "userId", peer.user)), peer::workspaceAllowed);
            } else if (type.equals("JOIN") && (root.size() == 3 || (root.size() == 4 && root.path("format").isTextual())) && root.path("documentId").isTextual() && peer.document == null) {
                java.util.UUID document;
                try { document = java.util.UUID.fromString(root.path("documentId").asText()); }
                catch (IllegalArgumentException ex) { peer.sender.close(CloseStatus.POLICY_VIOLATION); return; }
                var format = peer.readableFormat(document);
                if (format.isEmpty()) { peer.sender.close(CloseStatus.POLICY_VIOLATION); return; }
                String requested = root.size() == 3 ? "PLAIN_TEXT_V1" : root.path("format").asText();
                if (!formats.supports(format.get()) || !format.get().equals(requested)) {
                    peer.sender.send(json.writeValueAsString(Map.of("type", "REJECTED", "version", 1, "code", "SCHEMA_UNSUPPORTED")), peer::workspaceAllowed);
                    return;
                }
                peer.document = document;
                peer.sender.send(json.writeValueAsString(Map.of("type", "JOINED", "version", 1, "documentId", document, "format", format.get())), () -> document.equals(peer.document) && peer.allowed(document));
                peer.roomReady = true;
            } else if (type.equals("LEAVE") && root.size() == 2 && peer.document != null) {
                java.util.UUID document = peer.document;
                if (!peer.allowed(document)) { peer.sender.close(CloseStatus.POLICY_VIOLATION); return; }
                peer.roomReady = false;
                peer.document = null;
                peer.sender.send(json.writeValueAsString(Map.of("type", "LEFT", "version", 1, "documentId", document)), peer::workspaceAllowed);
            } else if (type.equals("SYNC") && root.size() == 4 && peer.document != null && root.path("epoch").isIntegralNumber()
                    && root.path("epoch").canConvertToLong() && root.path("epoch").longValue() >= 0 && root.path("after").isIntegralNumber()
                    && root.path("after").canConvertToLong() && root.path("after").longValue() >= 0) {
                var document = peer.document;
                try {
                    String page = delivery.sync(peer.workspace, document, peer.user, root.path("epoch").longValue(), root.path("after").longValue());
                    peer.sender.send(page, () -> document.equals(peer.document) && peer.allowed(document));
                } catch (DocumentUpdateException ex) {
                    peer.sender.send(json.writeValueAsString(Map.of("type", "REJECTED", "version", 1, "code", ex.code())), peer::workspaceAllowed);
                }
            } else if (type.equals("SUBMIT_UPDATE") && root.size() == 5 && peer.document != null && root.path("epoch").isIntegralNumber()
                    && root.path("epoch").canConvertToLong() && root.path("epoch").longValue() >= 0 && root.path("updateId").isTextual() && root.path("payload").isTextual()) {
                var document = peer.document;
                try {
                    String id = root.path("updateId").asText(), encoded = root.path("payload").asText();
                    java.util.UUID updateId = java.util.UUID.fromString(id);
                    if (!updateId.toString().equals(id) || encoded.length() > 10924) throw new IllegalArgumentException("Invalid update");
                    byte[] payload = java.util.Base64.getDecoder().decode(encoded);
                    if (payload.length == 0 || payload.length > 8192 || !java.util.Base64.getEncoder().encodeToString(payload).equals(encoded)) throw new IllegalArgumentException("Invalid update");
                    var saved = updates.submit(peer.workspace, document, peer.user, root.path("epoch").longValue(), updateId, payload,
                            socket.getAttributes().get("sessionGuard"), peer::sessionValid);
                    // submit returns only after TransactionTemplate has completed the database commit.
                    peer.sender.send(json.writeValueAsString(Map.of("type", "SAVED", "version", 1, "documentId", saved.documentId(),
                            "epoch", saved.epoch(), "sequence", saved.sequence(), "updateId", saved.updateId(), "duplicate", saved.duplicate())),
                            () -> document.equals(peer.document) && peer.allowed(document));
                } catch (IllegalArgumentException ex) {
                    peer.sender.send(json.writeValueAsString(Map.of("type", "REJECTED", "version", 1, "code", "INVALID_UPDATE")), peer::workspaceAllowed);
                } catch (DocumentUpdateException ex) {
                    peer.sender.send(json.writeValueAsString(Map.of("type", "REJECTED", "version", 1, "code", ex.code())), peer::workspaceAllowed);
                } catch (YjsEngineException ex) {
                    String code = ex.limitExceeded() ? "DOCUMENT_LIMIT" : ex.rejected() ? "INVALID_UPDATE" : "ENGINE_UNAVAILABLE";
                    peer.sender.send(json.writeValueAsString(Map.of("type", "REJECTED", "version", 1, "code", code)), peer::workspaceAllowed);
                } catch (RuntimeException ex) {
                    // The outcome may be unknown if commit failed after the database accepted it. Retry with the same update ID.
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("Document update failed: {}", ex.getClass().getName());
                    peer.sender.close(CloseStatus.SERVER_ERROR);
                }
            } else if (type.equals("TEST_MESSAGE") && root.size() == 3 && root.path("message").isTextual() && peer.document != null) {
                String body = root.path("message").asText();
                java.util.UUID document = peer.document;
                if (body.isBlank() || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 || !peer.allowed(document)) {
                    peer.sender.close(CloseStatus.POLICY_VIOLATION); return;
                }
                rooms.broadcast(peer, document, json.writeValueAsString(Map.of("type", "TEST_MESSAGE", "version", 1,
                        "documentId", document, "userId", peer.user, "message", body)));
            } else { peer.sender.close(CloseStatus.POLICY_VIOLATION); }
        } catch (tools.jackson.core.JacksonException ex) { socket.close(CloseStatus.BAD_DATA); return; }
    }

    @Override protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) throws IOException { socket.close(CloseStatus.NOT_ACCEPTABLE); }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) throws Exception { socket.close(CloseStatus.SERVER_ERROR); }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        rooms.remove(socket);
        registry.remove((String) socket.getAttributes().get("httpSessionId"), socket);
    }
}
