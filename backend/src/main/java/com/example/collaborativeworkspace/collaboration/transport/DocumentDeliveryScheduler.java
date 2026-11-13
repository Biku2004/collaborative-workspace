package com.example.collaborativeworkspace.collaboration.transport;

import com.example.collaborativeworkspace.observability.WorkspaceTelemetry;
import static com.example.collaborativeworkspace.observability.WorkspaceTelemetry.Stage.*;

import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "workspace.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentDeliveryScheduler {
    private WorkspaceTelemetry telemetry = WorkspaceTelemetry.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    void observe(WorkspaceTelemetry telemetry) { this.telemetry = telemetry; }
    private final DocumentDeliveryService delivery;
    private final DocumentUpdateRepository updates;
    private final DocumentRoomHub rooms;
    private final JsonMapper json;
    private final boolean relayEnabled;
    public DocumentDeliveryScheduler(DocumentDeliveryService delivery, DocumentUpdateRepository updates, DocumentRoomHub rooms, JsonMapper json,
            @org.springframework.beans.factory.annotation.Value("${workspace.delivery.relay-enabled:true}") boolean relayEnabled) {
        this.delivery = delivery; this.updates = updates; this.rooms = rooms; this.json = json; this.relayEnabled = relayEnabled;
    }
    @Scheduled(fixedDelayString = "${workspace.delivery.poll-ms:1000}")
    public void poll() {
        try { telemetry.measure(DELIVERY_POLL, () -> { if (relayEnabled) delivery.relayPending(); return null; }); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Delivery poll failed: {}", ex.getClass().getName()); }
        // Redis/relay failure must not suppress the durable-head repair or authorization sweep.
        try { rooms.notifyHeads(updates, json); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Head sweep failed: {}", ex.getClass().getName()); }
    }
}
