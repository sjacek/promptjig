package io.github.sjacek.promptjig.springai;

import io.github.sjacek.promptjig.core.ContractResponseValidator;
import io.github.sjacek.promptjig.core.PromptContract;
import io.github.sjacek.promptjig.core.ResponseViolation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import static io.github.sjacek.promptjig.springai.PromptTestSupport.text;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the host's Jackson-3-only native calls alongside Promptjig's portable validation boundary. */
@NullMarked
class ChatClientCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"v2", "v3"})
    void nativeChatClientValidationAndPromptjigReadingCoexistForBothBindings(String jackson) throws Exception {
        var contract = PromptContractRegistry.load(new ClassPathResource(
                "prompts/patient/disability/certificate-extraction.prompt.yaml"))
                .get("patient.disability.certificate-extraction");
        var runtime = PromptRuntime.builder().binding(jackson.equals("v2")
                ? PromptJsonBinding.jackson2() : PromptJsonBinding.jackson3()).build();
        var raw = new ClassPathResource("fixtures/valid-output.json").getContentAsString(StandardCharsets.UTF_8);
        var calls = new AtomicInteger();
        var model = new ChatModel() {
            @Override
            public ChatResponse call(@NonNull Prompt prompt) {
                calls.incrementAndGet();
                assertTrue(text(prompt.getInstructions().get(1)).contains("synthetic-testdata"));
                return new ChatResponse(List.of(new Generation(new AssistantMessage(raw))));
            }
        };
        // This advisor links directly to Networknt's Jackson-3 overloads in both construction and response validation.
        // It is a host-compatibility fixture, not the application's audit/repair policy. Only valid synthetic output is used.
        var advisor = StructuredOutputValidationAdvisor.builder().outputJsonSchema(runtime.responseFormat(contract))
                .maxRepeatAttempts(0).build();
        var response = ChatClient.builder(model).defaultAdvisors(advisor).build()
                .prompt(new Prompt(runtime.prompt(contract, Map.of("ocrText", "synthetic-testdata"))))
                .call().chatResponse();

        assertEquals(1, calls.get());
        if (response == null) throw new AssertionError("Missing synthetic chat response");
        var generation = response.getResult();
        if (generation == null) throw new AssertionError("Missing synthetic generation");
        var result = runtime.read(contract, text(generation.getOutput()), Map.class);
        assertTrue(result.accepted(), result.violations().toString());
        assertTrue(result.violations().isEmpty());
        var value = result.value();
        if (value == null) throw new AssertionError("Expected the accepted response");
        assertEquals("PL", value.get("country"));
        assertEquals("MODERATE", value.get("degree"));
    }

    @Test
    void hostValidatorPreservesDecimalPrecisionWhenRepairingAnInvalidNumericEnum() throws Exception {
        // ChatClient currently resolves 3.0.1, distinct from the newer 3.x version exercised by core:testValidator3.
        var schema = PromptContract.JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["amount"],"properties":{
                  "amount":{"type":["number","null"],"enum":[0.12345678901234567890123456789,null]}}}
                """);
        if (schema == null) throw new AssertionError("Missing decimal enum schema");
        var validator = ContractResponseValidator.builder().schema(schema).build();
        var result = validator.read("{\"amount\":0.12345678901234567890123456790}", false);

        assertTrue(result.accepted());
        var normalized = result.normalized();
        if (normalized == null) throw new AssertionError("Missing repaired numeric response");
        assertTrue(normalized.required("amount").isNull());
        assertEquals(1, result.violations().size());
        var finding = result.violations().get(0);
        assertEquals("/amount", finding.pointer());
        assertEquals("enum", finding.rule());
        assertEquals(ResponseViolation.Action.NULLIFIED, finding.action());
        assertFalse(result.violations().toString().contains("123456789"));
        assertFalse(validator.read("{\"amount\":0.12345678901234567890123456790}", true).accepted());
    }
}
