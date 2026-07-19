package dev.jwalker.controlplane.api.users.service;

// Business-rule violations in admin user management. The controller
// maps reason → HTTP status (DUPLICATE_EMAIL/SELF_MODIFICATION → 409,
// UNKNOWN_ROLE → 400) and surfaces the reason as a ProblemDetail
// property, matching the schedule/job exception pattern.
public class UserAdminException extends RuntimeException {

    public enum Reason {
        DUPLICATE_EMAIL,
        UNKNOWN_ROLE,
        SELF_MODIFICATION,
    }

    private final Reason reason;

    public UserAdminException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
