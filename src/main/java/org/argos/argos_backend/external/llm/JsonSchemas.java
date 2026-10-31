package org.argos.argos_backend.external.llm;

import java.lang.reflect.*;
import java.util.*;

/** Closed, fully-required JSON schemas for the record-based LLM outputs. */
final class JsonSchemas {
    private JsonSchemas() {}
    static Map<String, Object> of(Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class)
            return Map.of("type", "array", "items", of(p.getActualTypeArguments()[0]));
        if (!(type instanceof Class<?> cls)) throw new IllegalArgumentException("Unsupported schema type");
        if (cls == String.class) return Map.of("type", "string");
        if (cls == boolean.class) return Map.of("type", "boolean");
        if (cls == int.class || cls == Integer.class) return Map.of("type", "integer");
        if (cls.isEnum()) return Map.of("type", "string", "enum", Arrays.stream(cls.getEnumConstants()).map(Object::toString).toList());
        if (!cls.isRecord()) throw new IllegalArgumentException("Expected output record");
        Map<String, Object> props = new LinkedHashMap<>();
        for (RecordComponent field : cls.getRecordComponents()) props.put(field.getName(), of(field.getGenericType()));
        return Map.of("type", "object", "properties", props, "required", List.copyOf(props.keySet()), "additionalProperties", false);
    }
}
