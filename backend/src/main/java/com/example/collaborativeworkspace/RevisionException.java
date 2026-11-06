package com.example.collaborativeworkspace;

public class RevisionException extends RuntimeException {
    private final String code;
    public RevisionException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
