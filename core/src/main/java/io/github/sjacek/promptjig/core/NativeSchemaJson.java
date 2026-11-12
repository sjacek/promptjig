package io.github.sjacek.promptjig.core;

import com.networknt.schema.serialization.DefaultNodeReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Configures Networknt's private reader with exact decimal parsing, without linking to a Jackson-family setter.
 * The mapper is built once and never mutated or installed in Networknt's global mapper factory.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeSchemaJson {
    // The callback's builder class is stable, but jsonMapper's parameter changes between ObjectMapper 2 and 3.
    private static final Method JSON_MAPPER = Arrays.stream(DefaultNodeReader.Builder.class.getMethods())
            .filter(method -> method.getName().equals("jsonMapper") && method.getParameterCount() == 1)
            .findFirst().orElseThrow(() -> new IllegalStateException("Unsupported schema reader configuration API"));
    private static final Object MAPPER = exactMapper(JSON_MAPPER.getParameterTypes()[0]);

    static void configure(@NonNull DefaultNodeReader.Builder reader) {
        try {
            JSON_MAPPER.invoke(reader, MAPPER);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot configure exact schema JSON reading", exception);
        }
    }

    /**
     * Reflection is confined to constructing the resolved family's immutable mapper configuration.
     * Schema/data compilation and validation still use the public JSON-text API, with no reflection per value.
     * The core consequently needs no extra Jackson-3 dependency when used with standalone Networknt 2.
     */
    private static Object exactMapper(@NonNull Class<?> mapperType) {
        try {
            String namespace = mapperType.getPackageName();
            var loader = mapperType.getClassLoader();
            var feature = Class.forName(namespace + ".DeserializationFeature", true, loader);
            var flags = Array.newInstance(feature, 1);
            Array.set(flags, 0, feature.getField("USE_BIG_DECIMAL_FOR_FLOATS").get(null));
            var mapper = Class.forName(namespace + ".json.JsonMapper", true, loader);
            var builder = mapper.getMethod("builder").invoke(null);
            if (builder == null) throw new IllegalStateException("Missing schema JSON mapper builder");
            var configured = builder.getClass().getMethod("enable", flags.getClass()).invoke(builder, flags);
            if (configured == null) throw new IllegalStateException("Missing configured schema JSON mapper builder");
            var result = configured.getClass().getMethod("build").invoke(configured);
            if (!mapperType.isInstance(result)) throw new IllegalStateException("Incompatible schema JSON mapper");
            return result;
        } catch (ReflectiveOperationException exception) {
            // Configuration contains no authored schema or model response; the cause identifies unsupported APIs.
            throw new IllegalStateException("Cannot construct exact schema JSON mapper", exception);
        }
    }
}
