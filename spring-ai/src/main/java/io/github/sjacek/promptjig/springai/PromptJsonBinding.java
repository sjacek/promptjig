package io.github.sjacek.promptjig.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * JSON binding boundary for generated models; implementations must reject scalar coercion.
 * Custom implementations must be thread-safe when shared with a runtime, and must not log input or output.
 * Built-in codecs are configured once and never mutate their mappers after publication.
 */
public interface PromptJsonBinding {
    /**
     * Serializes typed input for schema validation before rendering.
     * @param input typed input
     * @return complete JSON
     * @throws Exception if serialization fails; runtime discards exception details
     */
    String write(@NonNull Object input) throws Exception;
    /**
     * Binds a previously validated and normalized JSON object.
     * Runtime treats a null return or an object incompatible with the requested class as a binding failure.
     * @param json accepted JSON
     * @param type generated output class
     * @return bound model, or null when the codec decodes JSON null; runtime rejects such a result
     * @throws Exception if strict binding fails; runtime discards exception details
     */
    @Nullable Object read(@NonNull String json, @NonNull Class<?> type) throws Exception;

    /** {@return isolated strict Jackson 2 binding, including Java time types} */
    static PromptJsonBinding jackson2() {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        return new PromptJsonBinding() {
            @Override public String write(@NonNull Object input) throws JsonProcessingException { return mapper.writeValueAsString(input); }
            @Override public @Nullable Object read(@NonNull String json, @NonNull Class<?> type) throws JsonProcessingException { return mapper.readValue(json, type); }
        };
    }
    /** {@return isolated strict Jackson 3 binding with built-in Java time support} */
    static PromptJsonBinding jackson3() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(tools.jackson.databind.cfg.DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        return new PromptJsonBinding() {
            @Override public String write(@NonNull Object input) { return mapper.writeValueAsString(input); }
            @Override public @Nullable Object read(@NonNull String json, @NonNull Class<?> type) { return mapper.readValue(json, type); }
        };
    }
}
