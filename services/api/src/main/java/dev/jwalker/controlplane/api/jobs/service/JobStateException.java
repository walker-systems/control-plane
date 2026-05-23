package dev.jwalker.controlplane.api.jobs.service;

public class JobStateException extends RuntimeException {

    public enum Reason {
        CANNOT_CANCEL,
        CANNOT_RETRY
    }

    private final Reason reason;

    public JobStateException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
