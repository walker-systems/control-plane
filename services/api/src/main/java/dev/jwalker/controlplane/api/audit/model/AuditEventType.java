package dev.jwalker.controlplane.api.audit.model;

public enum AuditEventType {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGOUT,
    TOKEN_REFRESHED,
    USER_CREATED,
    USER_ROLE_CHANGED,
    JOB_CREATED,
    JOB_CANCELLED,
    JOB_RETRIED,
    SCHEDULE_CREATED,
    SCHEDULE_PAUSED,
    SCHEDULE_RESUMED
}
