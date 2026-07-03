package dev.jwalker.controlplane.api.schedules.service;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import org.springframework.scheduling.support.CronExpression;

final class ScheduleCronCalculator {

    private ScheduleCronCalculator() {}

    static CronExpression parseCron(String expression) {
        try {
            return CronExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.INVALID_CRON,
                    "Invalid cron expression: " + expression);
        }
    }

    static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.INVALID_TIMEZONE,
                    "Invalid timezone: " + timezone);
        }
    }

    // Next fire time strictly after the given reference instant. Returns null
    // if the cron has no further matches within Spring's search bound.
    static OffsetDateTime nextAfter(CronExpression cron, ZoneId zone, OffsetDateTime reference) {
        ZonedDateTime referenceInZone = reference.atZoneSameInstant(zone);
        Temporal next = cron.next(referenceInZone);
        return next == null ? null : ((ZonedDateTime) next).toOffsetDateTime();
    }
}
