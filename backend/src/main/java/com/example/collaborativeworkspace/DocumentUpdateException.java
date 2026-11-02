package com.example.collaborativeworkspace;

/** Safe protocol error code; never includes payloads, credentials or database details. */
public class DocumentUpdateException extends RuntimeException {
    private final String code;
    public DocumentUpdateException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
