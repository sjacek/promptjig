package example.announcement;

import com.openai.errors.OpenAIInvalidDataException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Cause-based diagnostics remain independent of model latency, body content and exception wording. */
class ModelFailureDiagnosticsTest {
    @ParameterizedTest
    @MethodSource("causes")
    void classifiesTypedNestedCausesWithoutStoringTheirMessages(Throwable cause, ModelFailureDiagnostics.Category category) {
        var failure = ModelFailureDiagnostics.from(new OpenAIInvalidDataException("private-wrapper-testdata", cause), false);
        assertEquals(category, failure.category());
        assertTrue(failure.causeChainComplete());
        assertEquals(cause.getClass().getName(), failure.causeClasses().get(1));
        assertDoesNotThrow(() -> Instant.parse(failure.observedAtUtc()));
        var json = JsonMapper.builder().build().writeValueAsString(failure);
        assertFalse(json.contains("private-wrapper-testdata"));
        assertFalse(json.contains("private-cause-testdata"));
        assertThrows(UnsupportedOperationException.class, failure.causeClasses()::clear);
    }

    static Stream<Arguments> causes() {
        return Stream.of(
                Arguments.of(new SocketTimeoutException("private-cause-testdata"), ModelFailureDiagnostics.Category.TIMEOUT),
                Arguments.of(new HttpTimeoutException("private-cause-testdata"), ModelFailureDiagnostics.Category.TIMEOUT),
                Arguments.of(new TimeoutException("private-cause-testdata"), ModelFailureDiagnostics.Category.TIMEOUT),
                Arguments.of(new InterruptedByTimeoutException(), ModelFailureDiagnostics.Category.TIMEOUT),
                Arguments.of(new ConnectException("private-cause-testdata"), ModelFailureDiagnostics.Category.CONNECTION),
                Arguments.of(new NoRouteToHostException("private-cause-testdata"), ModelFailureDiagnostics.Category.CONNECTION),
                Arguments.of(new UnknownHostException("private-cause-testdata"), ModelFailureDiagnostics.Category.CONNECTION),
                Arguments.of(new IOException("private-cause-testdata"), ModelFailureDiagnostics.Category.IO));
    }

    @Test
    void neverInfersTimeoutFromMessageOrUnknownInvalidResponse() {
        var invalid = ModelFailureDiagnostics.from(new OpenAIInvalidDataException("timeout after 600 seconds"), false);
        assertEquals(ModelFailureDiagnostics.Category.INVALID_RESPONSE, invalid.category());
        var unknown = ModelFailureDiagnostics.from(new IllegalStateException("timeout after 600 seconds"), false);
        assertEquals(ModelFailureDiagnostics.Category.UNKNOWN, unknown.category());
        assertEquals(ModelFailureDiagnostics.Category.INTERRUPTED_IO,
                ModelFailureDiagnostics.from(new java.io.InterruptedIOException("timeout"), false).category());
        assertEquals(ModelFailureDiagnostics.Category.RESPONSE_PROCESSING,
                ModelFailureDiagnostics.from(new IllegalStateException("length"), true).category());
    }

    @Test
    void neverReadsMessageStackOrSuppressedFailures() {
        IOException sensitive = new IOException() {
            @Override public String getMessage() { throw new AssertionError("message accessed"); }
            @Override public String getLocalizedMessage() { throw new AssertionError("localized message accessed"); }
            @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("stack accessed"); }
            @Override public String toString() { throw new AssertionError("body accessed"); }
        };
        sensitive.addSuppressed(new SocketTimeoutException("unrelated-timeout-testdata"));
        var failure = ModelFailureDiagnostics.from(sensitive, false);
        assertEquals(ModelFailureDiagnostics.Category.IO, failure.category());
        assertEquals(1, failure.causeClasses().size(), "Suppressed cleanup failures do not prove the call timed out");
    }

    @Test
    void localProcessingFailureRetainsTimeoutEvidenceWithoutClaimingTransportFailure() {
        var failure = ModelFailureDiagnostics.from(new IllegalStateException("private-processing-testdata",
                new SocketTimeoutException("private-cause-testdata")), true);
        assertEquals(ModelFailureDiagnostics.Category.RESPONSE_PROCESSING, failure.category());
        assertEquals(List.of(IllegalStateException.class.getName(), SocketTimeoutException.class.getName()), failure.causeClasses());
        assertTrue(failure.causeChainComplete());
        assertNull(failure.httpStatus());
    }

    @Test
    void recognizesTimeoutAtObservationBoundaryWithoutClaimingCompletenessBeyondIt() {
        Throwable chain = new SocketTimeoutException();
        for (int index = 1; index < 32; index++) chain = new RuntimeException(chain);
        var complete = ModelFailureDiagnostics.from(chain, false);
        assertEquals(32, complete.causeClasses().size());
        assertTrue(complete.causeChainComplete());
        assertEquals(ModelFailureDiagnostics.Category.TIMEOUT, complete.category());

        var truncated = ModelFailureDiagnostics.from(new RuntimeException(chain), false);
        assertEquals(32, truncated.causeClasses().size());
        assertFalse(truncated.causeChainComplete());
        assertEquals(ModelFailureDiagnostics.Category.UNKNOWN, truncated.category(), "A cause beyond the limit is not evidence");
    }

    @Test
    void callerCannotChangeEvidenceAfterBuildingDiagnostics() {
        var classes = new ArrayList<>(List.of(IOException.class.getName()));
        var failure = ModelFailureDiagnostics.builder().category(ModelFailureDiagnostics.Category.IO)
                .exceptionClass(IOException.class.getName()).causeClasses(classes).causeChainComplete(true)
                .observedAtUtc(Instant.EPOCH.toString()).build();
        classes.add(SocketTimeoutException.class.getName());
        assertEquals(List.of(IOException.class.getName()), failure.causeClasses());
        assertThrows(UnsupportedOperationException.class, failure.causeClasses()::clear);
    }

    @Test
    void boundsCyclesAndLongCauseChains() {
        var first = new RuntimeException();
        var second = new RuntimeException(first);
        first.initCause(second);
        var cyclic = ModelFailureDiagnostics.from(first, false);
        assertFalse(cyclic.causeChainComplete());
        assertEquals(2, cyclic.causeClasses().size());
        Throwable longChain = new SocketTimeoutException();
        for (int index = 0; index < 40; index++) longChain = new RuntimeException(longChain);
        var bounded = ModelFailureDiagnostics.from(longChain, false);
        assertFalse(bounded.causeChainComplete());
        assertEquals(32, bounded.causeClasses().size());
        assertEquals(ModelFailureDiagnostics.Category.UNKNOWN, bounded.category(), "An unobserved cause must stay unknown");
    }
}
