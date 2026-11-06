package com.example.collaborativeworkspace.common.pagination;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record PageSlice<T>(List<T> items, UUID nextCursor) {
    public static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new InvalidDocumentPageException();
    }

    public static <T> PageSlice<T> from(List<T> rows, int limit, Function<T, UUID> id) {
        boolean more = rows.size() > limit;
        List<T> items = List.copyOf(more ? rows.subList(0, limit) : rows);
        return new PageSlice<>(items, more ? id.apply(items.get(items.size() - 1)) : null);
    }
}
