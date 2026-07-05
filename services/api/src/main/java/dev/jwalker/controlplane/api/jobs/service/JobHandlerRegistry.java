package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.JobType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Autowires every JobHandler @Component in the context and indexes them
// by JobType. If two handlers claim the same type we log a warning and
// keep whichever registered first — deterministic but signals a bug.
@Slf4j
@Component
public class JobHandlerRegistry {

    private final Map<JobType, JobHandler> byType;

    public JobHandlerRegistry(List<JobHandler> handlers) {
        EnumMap<JobType, JobHandler> map = new EnumMap<>(JobType.class);
        for (JobHandler handler : handlers) {
            JobHandler previous = map.putIfAbsent(handler.getSupportedType(), handler);
            if (previous != null) {
                log.warn(
                        "Multiple JobHandler beans registered for {}: keeping {}, ignoring {}",
                        handler.getSupportedType(),
                        previous.getClass().getSimpleName(),
                        handler.getClass().getSimpleName());
            }
        }
        this.byType = Map.copyOf(map);
    }

    public Optional<JobHandler> handlerFor(JobType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
