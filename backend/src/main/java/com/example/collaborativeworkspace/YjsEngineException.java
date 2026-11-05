package com.example.collaborativeworkspace;

/** An engine rejection or an unavailable engine; neither means an update was saved. */
public class YjsEngineException extends RuntimeException {
    private final boolean rejected;
    private final boolean limitExceeded;
    public YjsEngineException(boolean rejected) { this(rejected, false); }
    public YjsEngineException(boolean rejected, boolean limitExceeded) {
        super(rejected ? "Candidate rejected" : "Engine unavailable");
        if (limitExceeded && !rejected) throw new IllegalArgumentException("Limit failure must be a rejection");
        this.rejected = rejected; this.limitExceeded = limitExceeded;
    }
    public boolean rejected() { return rejected; }
    public boolean limitExceeded() { return limitExceeded; }
}
