package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the actual JSON boundary under Networknt 2 and 3, including cases that lossy text conversion would hide. */
class SchemaValidationCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a/b~🙂\":[null,true,\"Zażółć\\n\\\"\\\\\"]}",
            "[false,null,{\"nested\":[]}]", "\"Łódź 🙂\\n\\t\\\"\\\\\"", "true", "null", "42", "0.125"
    })
    void preservesJsonKindsEscapesAndUnicodeWithoutMutatingTheInput(String json) throws Exception {
        var value = parse(json);
        var original = value.deepCopy();
        var schema = PromptContract.JSON.createObjectNode();
        schema.putArray("enum").add(value);
        var compiled = SchemaValidation.compile(schema);

        assertTrue(SchemaValidation.validate(compiled, value).isEmpty());
        assertEquals(original, value);
        assertFalse(SchemaValidation.validate(compiled, PromptContract.JSON.createObjectNode().put("different", true)).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("preciseNumbers")
    void distinguishesNumbersThatWouldCollapseToTheSameDouble(JsonNode exact, JsonNode different) {
        var schema = PromptContract.JSON.createObjectNode();
        schema.putArray("enum").add(exact);
        var compiled = SchemaValidation.compile(schema);

        assertEquals(exact.doubleValue(), different.doubleValue(), "The fixture must expose a double-rounding error");
        assertTrue(SchemaValidation.validate(compiled, exact).isEmpty());
        assertEquals(List.of("enum"), SchemaValidation.validate(compiled, different).stream().map(Error::getKeyword).toList());
    }

    private static Stream<Arguments> preciseNumbers() {
        var nodes = PromptContract.JSON.getNodeFactory();
        var integer = new BigInteger("900719925474099312345678901234567890");
        var decimal = new BigDecimal("0.12345678901234567890123456789");
        return Stream.of(
                Arguments.of(nodes.numberNode(integer), nodes.numberNode(integer.add(BigInteger.ONE))),
                Arguments.of(nodes.numberNode(decimal), nodes.numberNode(decimal.add(new BigDecimal("0.00000000000000000000000000001")))));
    }

    @Test
    void compiledRulesAreIndependentOfLaterSchemaMutationsAndEarlierFailures() {
        var authored = PromptContract.JSON.createObjectNode().put("type", "string");
        var compiled = SchemaValidation.compile(authored);
        authored.put("type", "integer");
        var text = PromptContract.JSON.getNodeFactory().textNode("synthetic-testdata");
        var number = PromptContract.JSON.getNodeFactory().numberNode(7);

        assertEquals("type", SchemaValidation.validate(compiled, number).get(0).getKeyword());
        assertTrue(SchemaValidation.validate(compiled, text).isEmpty());
        assertTrue(SchemaValidation.validate(SchemaValidation.compile(authored), number).isEmpty());
    }

    @Test
    @Timeout(10)
    void sharedCompiledSchemaKeepsConcurrentValidationsIndependent() throws Exception {
        var compiled = SchemaValidation.compile(parse("""
                {"type":"object","required":["code"],"properties":{"code":{"type":"string"}}}
                """));
        // The common pool is JVM-owned; this test creates no executor requiring a Java-19 close API.
        var calls = IntStream.range(0, 48).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
            var value = PromptContract.JSON.createObjectNode();
            if (index % 2 == 0) value.put("code", "synthetic-testdata");
            var errors = SchemaValidation.validate(compiled, value);
            if (index % 2 == 0) assertTrue(errors.isEmpty());
            else {
                assertEquals(1, errors.size());
                assertEquals("required", errors.get(0).getKeyword());
                assertEquals("", errors.get(0).getInstanceLocation().toString());
                assertEquals("code", errors.get(0).getProperty());
            }
            return true;
        })).toList();
        calls.forEach(call -> assertTrue(call.join()));
    }

    @Test
    void externalReferencesFailWithoutContactingEvenALocalSchemaServer() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            byte[] reply = "{\"type\":\"string\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/schema+json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            var schema = PromptContract.JSON.createObjectNode()
                    .put("$ref", "http://127.0.0.1:" + server.getAddress().getPort() + "/schema");
            assertThrows(RuntimeException.class, () -> SchemaValidation.validate(SchemaValidation.compile(schema),
                    PromptContract.JSON.getNodeFactory().textNode("synthetic-testdata")));
            assertEquals(0, requests.get(), "Schema validation must stay offline");
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode parse(@NonNull String json) throws Exception {
        var value = PromptContract.JSON.readTree(json);
        if (value == null) throw new AssertionError("Missing JSON test value");
        return value;
    }
}
