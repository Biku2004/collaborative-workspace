package com.example.collaborativeworkspace.documents.history;

import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Bound checkpoint, revision, restore and projection work together; edit traffic has a separate engine opportunity. */
@Component
@Profile("dev")
public class DocumentSnapshotBudget {
    private final Semaphore slot = new Semaphore(1);
    public boolean acquire() { return slot.tryAcquire(); }
    public void release() { slot.release(); }
}
