package com.yourco.ivr.engine.response;

import com.yourco.ivr.api.dto.ProcessingEvent;

import java.util.List;

/**
 * Small helper for appending {@link ProcessingEvent} entries to a session's processing log.
 *
 * <p>Centralises the {@code log.add(ProcessingEvent.builder()...)} boilerplate that was
 * previously duplicated as a private {@code addEntry} in every engine collaborator
 * ({@link com.yourco.ivr.engine.AuthEngine}, {@link com.yourco.ivr.engine.path.ActivePathManager},
 * {@link com.yourco.ivr.engine.AttemptCoordinator}).
 */
public final class ProcessingLog {

    private ProcessingLog() {
    }

    /** Appends an entry with the given level (e.g. {@code "INFO"}, {@code "WARN"}, {@code "PASS"}). */
    public static void add(List<ProcessingEvent> log, String level, String message) {
        log.add(ProcessingEvent.builder().level(level).message(message).build());
    }
}
