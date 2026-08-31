package com.perf.globalorchestrator.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns a cached value into the bytes stored in {@code ORCH_CACHE.CACHE_VALUE}
 * and back: JSON, then gzip.
 *
 * <p>JSON rather than JDK serialization because the cached DTOs are Java
 * records, which are not {@code Serializable}. Default typing is
 * {@code EVERYTHING} so the {@code @class} tag is written even for {@code final}
 * records — without it a polymorphic round-trip cannot resolve its concrete
 * type on read.
 *
 * <p>Gzip because the store is a database, not a memory grid: these values
 * compress 8–12×, which is the difference between a cache hit reading one
 * inline block and reading a separate LOB segment.
 */
public final class CacheValueCodec {

    private final ObjectMapper mapper;

    public CacheValueCodec() {
        this(defaultMapper());
    }

    CacheValueCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The mapper the cache round-trips through. Package-private so
     * {@code CacheSerializationTest} can pin the record shapes it must carry.
     */
    static ObjectMapper defaultMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    /** JSON → gzip. */
    public byte[] encode(Object value) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, json.length / 8));
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(json);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cache value could not be encoded", e);
        }
    }

    /** gunzip → JSON. */
    public Object decode(byte[] stored) {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(stored))) {
            return mapper.readValue(gz.readAllBytes(), Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cache value could not be decoded", e);
        }
    }
}
