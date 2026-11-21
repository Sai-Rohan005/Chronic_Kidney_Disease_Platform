package services;

/** Classified failures; retry only transient failures of operations known to be idempotent. */
public class UpstreamException extends RuntimeException {
    private final int status;
    private final boolean retryable;
    public UpstreamException(int status, boolean retryable, String message, Throwable cause) {
        super(message, cause); this.status = status; this.retryable = retryable;
    }
    public int status() { return status; }
    public boolean retryable() { return retryable; }
}
