package com.example.collaborativeworkspace;

import java.util.List;

public record DocumentPage(List<Document> items, String nextCursor) {}
