package io.github.jdubois.bootui.engine.mongodb;

import java.lang.reflect.RecordComponent;
import java.util.Collection;

/** Conservative JSON-escaped size accounting of our own neutral records, not a JSON serializer. */
final class MongoDbRetentionBudget {
    private final int maxItems;
    private final int maxBytes;
    private int items;
    private long bytes = 8192; // Reserve envelope and bounded omission explanations.
    private String exhausted;

    MongoDbRetentionBudget(MongoDbSettings settings) {
        maxItems = settings.maxTotalItems();
        maxBytes = settings.maxMetadataBytes();
    }

    boolean retain(Object value) {
        long size = bytes(value);
        int count = items(value);
        if (count > maxItems - items || size > maxBytes - bytes) {
            exhausted = count > maxItems - items ? "ITEM_LIMIT" : "BYTE_LIMIT";
            return false;
        }
        items += count;
        bytes += size;
        return true;
    }

    String exhausted() {
        return exhausted;
    }

    int items() {
        return items;
    }

    long bytes() {
        return bytes;
    }

    static long bytes(Object value) {
        if (value == null) return 4;
        if (value instanceof String text) {
            long size = 2;
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                size += character < 0x20 || character >= 0x7f
                        ? 6
                        : character == '"' || character == '\\' || character == '/' ? 2 : 1;
            }
            return size;
        }
        if (value instanceof Number || value instanceof Boolean) return 32;
        if (value instanceof Collection<?> list) {
            long size = 2;
            for (Object item : list) size += bytes(item) + 1;
            return size;
        }
        if (!value.getClass().isRecord()) throw new IllegalArgumentException("Only neutral records can be retained");
        long size = 2;
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                size += bytes(component.getName())
                        + bytes(component.getAccessor().invoke(value))
                        + 2;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot account for MongoDB DTO");
            }
        }
        return size;
    }

    private static int items(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> collection) {
            return collection.stream().mapToInt(MongoDbRetentionBudget::items).sum();
        }
        if (!value.getClass().isRecord()) return 1;
        int count = 1;
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                Object child = component.getAccessor().invoke(value);
                if (child instanceof Collection<?>
                        || child != null && child.getClass().isRecord()) count += items(child);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot account for MongoDB DTO");
            }
        }
        return count;
    }
}
