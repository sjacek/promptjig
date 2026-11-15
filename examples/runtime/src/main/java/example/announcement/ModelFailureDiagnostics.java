package example.announcement;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Value-free failure evidence. Classification uses types in a bounded, cycle-safe cause chain,
 * never exception messages, response bodies, stack traces, suppressed errors or elapsed-time guesses.
 * A non-timeout category means no recognized timeout cause was found, not proof that none occurred.
 * @param category strongest recognized failure category, with response-processing errors kept separate
 * @param exceptionClass top-level class name only
 * @param causeClasses observed class names, outermost first, excluding messages
 * @param causeChainComplete false when the chain cycles or exceeds the observation bound
 * @param httpStatus numeric SDK status when available; headers/body are never accessed
 * @param observedAtUtc client observation time for correlation with sanitized runner events
 */
@Builder
public record ModelFailureDiagnostics(@NonNull Category category, @NonNull String exceptionClass,
        @NonNull List<String> causeClasses, boolean causeChainComplete,
        @Nullable Integer httpStatus, @NonNull String observedAtUtc) {
    // Reports retain a snapshot even when the caller later reuses its mutable collection.
    public ModelFailureDiagnostics { causeClasses = List.copyOf(causeClasses); }

    /** Evidence categories; UNKNOWN and INTERRUPTED_IO deliberately make no timeout claim. */
    public enum Category { TIMEOUT, CONNECTION, HTTP_STATUS, INVALID_RESPONSE, INTERRUPTED_IO, IO, RESPONSE_PROCESSING, UNKNOWN }

    /**
     * Captures cause types without changing a request, its timeout, retry policy or case outcome.
     * Once a reply has returned, the failure belongs to local response processing. Otherwise typed
     * timeouts take precedence over HTTP status, connection errors, interrupted I/O, other I/O and
     * invalid-response wrappers. Numeric HTTP evidence is retained regardless of the selected category.
     * @param exception caught failure
     * @param replyReturned whether provider metadata was already captured before local response processing
     * @return bounded, serializable evidence; unknown causes remain unknown
     */
    public static ModelFailureDiagnostics from(@NonNull Throwable exception, boolean replyReturned) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var names = new ArrayList<String>();
        boolean timeout = false, connection = false, invalid = false, io = false, interruptedIo = false;
        Integer status = null;
        Throwable current = exception;
        // Identity avoids exception-defined equality; the bound also limits reports for acyclic chains.
        while (current != null && names.size() < 32 && seen.add(current)) {
            names.add(current.getClass().getName());
            timeout |= current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException || current instanceof InterruptedByTimeoutException;
            connection |= current instanceof ConnectException || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException;
            invalid |= current instanceof OpenAIInvalidDataException;
            io |= current instanceof IOException;
            // OkHttp can wrap its call timeout as generic InterruptedIOException. That type also
            // represents other interruptions, so preserve the evidence without claiming a timeout.
            interruptedIo |= current instanceof InterruptedIOException;
            if (status == null && current instanceof OpenAIServiceException service) status = service.statusCode();
            current = current.getCause();
        }
        var category = replyReturned ? Category.RESPONSE_PROCESSING : timeout ? Category.TIMEOUT
                : status != null ? Category.HTTP_STATUS : connection ? Category.CONNECTION
                : interruptedIo ? Category.INTERRUPTED_IO : io ? Category.IO
                : invalid ? Category.INVALID_RESPONSE : Category.UNKNOWN;
        return builder().category(category).exceptionClass(exception.getClass().getName()).causeClasses(names)
                .causeChainComplete(current == null).httpStatus(status).observedAtUtc(Instant.now().toString()).build();
    }
}
