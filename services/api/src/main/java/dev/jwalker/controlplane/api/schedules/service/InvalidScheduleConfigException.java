package dev.jwalker.controlplane.api.schedules.service;

public class InvalidScheduleConfigException extends RuntimeException {

    public enum Reason {
        INVALID_CRON,
        INVALID_TIMEZONE,
        DUPLICATE_NAME
    }

    private final Reason reason;

    public InvalidScheduleConfigException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
