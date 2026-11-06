package com.example.collaborativeworkspace;

import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Bound checkpoint and revision work together, leaving another engine slot for edits. */
@Component
@Profile("dev")
public class DocumentSnapshotBudget {
    private final Semaphore slot = new Semaphore(1);
    public boolean acquire() { return slot.tryAcquire(); }
    public void release() { slot.release(); }
}
