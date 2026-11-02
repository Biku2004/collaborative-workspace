package com.example.collaborativeworkspace;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the HTTP body subscription before a remote peer can buffer an unbounded response. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private Flow.Subscription subscription;
    private long size;
    private boolean terminated;
    BoundedBodySubscriber(long limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
    @Override public void onNext(List<ByteBuffer> items) {
        if (terminated) return;
        for (ByteBuffer item : items) {
            size += item.remaining();
            if (size > limit) {
                terminated = true;
                subscription.cancel();
                delegate.onError(new IOException("Engine response exceeds configured limit"));
                return;
            }
        }
        delegate.onNext(items);
    }
    @Override public void onError(Throwable failure) { if (!terminated) { terminated = true; delegate.onError(failure); } }
    @Override public void onComplete() { if (!terminated) { terminated = true; delegate.onComplete(); } }
}
