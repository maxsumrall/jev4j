package io.github.maxsumrall.jev4j;

/** Indicates that an evaluation could not be completed or its response was invalid. */
public final class JevEvaluationException extends RuntimeException {
  public JevEvaluationException(String message) {
    super(message);
  }

  public JevEvaluationException(String message, Throwable cause) {
    super(message, cause);
  }
}
