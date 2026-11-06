package com.example.collaborativeworkspace.documents.history;

/** Expected checkpoint failure without exposing document contents or database details. */
public class CheckpointException extends RuntimeException {
    private final String code;
    public CheckpointException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
