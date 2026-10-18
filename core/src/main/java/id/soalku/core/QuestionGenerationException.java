package id.soalku.core;

public final class QuestionGenerationException extends RuntimeException {
    public enum Reason { NOT_CONFIGURED, UNAVAILABLE, TIMEOUT, INVALID_OUTPUT, INVALID_INPUT, BUSY, REJECTED }
    private final Reason reason;
    public QuestionGenerationException(Reason reason, String message) { super(message); this.reason = reason; }
    public QuestionGenerationException(Reason reason, String message, Throwable cause) { super(message,cause); this.reason = reason; }
    public Reason reason() { return reason; }
}
