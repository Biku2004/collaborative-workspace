package com.example.collaborativeworkspace.documents;

import java.util.List;

public record DocumentPage(List<Document> items, String nextCursor) {}
