package com.example.collaborativeworkspace;

/** An engine rejection or an unavailable engine; neither means an update was saved. */
public class YjsEngineException extends RuntimeException {
    private final boolean rejected;
    public YjsEngineException(boolean rejected) { super(rejected ? "Candidate rejected" : "Engine unavailable"); this.rejected = rejected; }
    public boolean rejected() { return rejected; }
}
