package com.example.collaborativeworkspace.collaboration.cluster;

import java.util.UUID;

public interface ClusterPublisher {
    /** Publish only a pointer to an already committed outbox event. Failure leaves the event pending. */
    void update(UUID event);
}
