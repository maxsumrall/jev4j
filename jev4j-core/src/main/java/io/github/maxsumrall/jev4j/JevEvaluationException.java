package io.github.maxsumrall.jev4j;

import java.util.OptionalInt;

/** Indicates that an evaluation could not be completed or its response was invalid. */
public final class JevEvaluationException extends RuntimeException {
  private final OptionalInt httpStatusCode;

  public JevEvaluationException(String message) {
    super(message);
    httpStatusCode = OptionalInt.empty();
  }

  public JevEvaluationException(String message, Throwable cause) {
    super(message, cause);
    httpStatusCode = OptionalInt.empty();
  }

  public JevEvaluationException(String message, int httpStatusCode) {
    super(message);
    if (httpStatusCode < 100 || httpStatusCode > 599)
      throw new IllegalArgumentException("HTTP status code out of range");
    this.httpStatusCode = OptionalInt.of(httpStatusCode);
  }

  /** Returns the response status for HTTP failures, or empty for other evaluation failures. */
  public OptionalInt httpStatusCode() {
    return httpStatusCode;
  }
}
