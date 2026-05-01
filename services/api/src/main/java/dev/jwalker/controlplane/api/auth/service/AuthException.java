package dev.jwalker.controlplane.api.auth.service;

public class AuthException extends RuntimeException {

    public enum Reason {
        INVALID_CREDENTIALS,
        ACCOUNT_DISABLED,
        ACCOUNT_LOCKED,
        INVALID_REFRESH_TOKEN
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
