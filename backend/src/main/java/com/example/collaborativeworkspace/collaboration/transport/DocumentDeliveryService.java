package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import static com.example.collaborativeworkspace.observability.WorkspaceTelemetry.Stage.*;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateException;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.documents.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("dev")
public class DocumentDeliveryService {
    private com.example.collaborativeworkspace.collaboration.cluster.ClusterPublisher cluster;
    @org.springframework.beans.factory.annotation.Autowired
    void cluster(org.springframework.beans.factory.ObjectProvider<com.example.collaborativeworkspace.collaboration.cluster.ClusterPublisher> publisher) { cluster = publisher.getIfAvailable(); }
    private WorkspaceTelemetry telemetry = WorkspaceTelemetry.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    void observe(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    private static final int MAX_UPDATES = 8, MAX_PAGE_BYTES = 14000;
    private final DocumentUpdateRepository updates;
    private final DocumentRoomHub rooms;
    private final JsonMapper json;
    private final CollaborationFormatPolicy formats;

    public DocumentDeliveryService(DocumentUpdateRepository updates, DocumentRoomHub rooms, JsonMapper json, CollaborationFormatPolicy formats) {
        this.updates = updates; this.rooms = rooms; this.json = json; this.formats = formats;
    }

    /** A page is a consistent database snapshot; the client tracks the last contiguous applied sequence. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String sync(UUID workspace, UUID document, UUID user, long epoch, long after) {
        return telemetry.measure(SYNC, () -> syncMeasured(workspace, document, user, epoch, after));
    }
    private String syncMeasured(UUID workspace, UUID document, UUID user, long epoch, long after) {
        var head = updates.findAccessible(workspace, document, user).orElseThrow(() -> new DocumentUpdateException("NOT_FOUND"));
        if (!formats.supports(head.collaborationFormat())) throw new DocumentUpdateException("SCHEMA_UNSUPPORTED");
        if (epoch != head.epoch()) throw new DocumentUpdateException("EPOCH_CHANGED");
        if (after < 0 || after > head.sequence()) throw new DocumentUpdateException("INVALID_CURSOR");
        List<DocumentUpdateRepository.Update> rows = updates.findAfter(document, epoch, after, head.sequence(), MAX_UPDATES + 1);
        var page = new ArrayList<Map<String, Object>>();
        long cursor = after;
        for (var update : rows) {
            if (page.size() == MAX_UPDATES) break;
            if (update.sequence() != cursor + 1) throw new IllegalStateException("Document update log contains a sequence gap");
            var item = updateObject(update);
            var trial = new ArrayList<>(page); trial.add(item);
            String candidate = pageJson(document, epoch, update.sequence(), head.sequence(), trial);
            if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_PAGE_BYTES) {
                if (page.isEmpty()) throw new IllegalStateException("A stored update exceeds the sync page limit");
                break;
            }
            page.add(item); cursor = update.sequence();
        }
        if (cursor < head.sequence() && rows.isEmpty()) throw new IllegalStateException("Document update log is incomplete");
        return pageJson(document, epoch, cursor, head.sequence(), page);
    }

    private String pageJson(UUID document, long epoch, long after, long head, List<Map<String, Object>> page) {
        return json.writeValueAsString(Map.of("type", "SYNC_PAGE", "version", 1, "documentId", document,
                "epoch", epoch, "after", after, "head", head, "hasMore", after < head, "updates", page));
    }

    private Map<String, Object> updateObject(DocumentUpdateRepository.Update update) {
        return Map.of("sequence", update.sequence(), "updateId", update.updateId(), "userId", update.actor(),
                "payload", Base64.getEncoder().encodeToString(update.payload()));
    }

    /** Events refer only to already-committed update rows; duplicate notifications are allowed. */
    @Transactional
    public int relayPending() {
        var events = updates.lockPendingEvents(32);
        for (var event : events) {
            telemetry.measure(RELAY, event.correlationId(), () -> {
                var update = event.update();
                String message = json.writeValueAsString(Map.of("type", "UPDATE", "version", 1, "documentId", update.documentId(),
                        "epoch", update.epoch(), "sequence", update.sequence(), "updateId", update.updateId(), "userId", update.actor(),
                        "payload", Base64.getEncoder().encodeToString(update.payload())));
                rooms.broadcastCommitted(event.workspaceId(), update.documentId(), message, event.correlationId());
                if (cluster != null) cluster.update(event.eventId());
                updates.markRelayAttempted(event.eventId());
                return null;
            });
        }
        return events.size();
    }
}
