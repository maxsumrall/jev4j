package io.github.maxsumrall.jev4j;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Indicates that an evaluation could not be completed or its response was invalid. */
public final class JevEvaluationException extends RuntimeException {
    /** Broad failure kinds; these do not imply that retrying is safe. */
    public enum FailureCategory {
        HTTP,
        TIMEOUT,
        IO,
        INTERRUPTED,
        MALFORMED_RESPONSE,
        /** Used by legacy constructors without an explicit failure kind. */
        UNKNOWN
    }

    private final OptionalInt httpStatusCode;
    private final FailureCategory category;
    private final Optional<String> requestId;

    public JevEvaluationException(String message) {
        this(message, FailureCategory.UNKNOWN, OptionalInt.empty(), Optional.empty());
    }

    public JevEvaluationException(String message, Throwable cause) {
        super(message, cause);
        httpStatusCode = OptionalInt.empty();
        category = FailureCategory.UNKNOWN;
        requestId = Optional.empty();
    }

    public JevEvaluationException(String message, int httpStatusCode) {
        this(message, FailureCategory.HTTP, OptionalInt.of(httpStatusCode), Optional.empty());
    }

    JevEvaluationException(
            String message,
            FailureCategory category,
            OptionalInt httpStatusCode,
            Optional<String> requestId) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.httpStatusCode = Objects.requireNonNull(httpStatusCode, "httpStatusCode");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        if (httpStatusCode.isPresent()
                && (httpStatusCode.getAsInt() < 100 || httpStatusCode.getAsInt() > 599))
            throw new IllegalArgumentException("HTTP status code out of range");
    }

    public FailureCategory category() {
        return category;
    }

    /** Returns the provider's request-ID header, independently of any response-body ID. */
    public Optional<String> requestId() {
        return requestId;
    }

    /** Returns the response status for HTTP failures, or empty for other evaluation failures. */
    public OptionalInt httpStatusCode() {
        return httpStatusCode;
    }
}
