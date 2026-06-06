package com.yourco.ivr.repository;

import com.yourco.ivr.domain.IvrSession;

/**
 * Persistence contract for {@link IvrSession} objects.
 *
 * <p>The sole implementation is {@link SqliteSessionRepository}, which uses
 * {@code JdbcTemplate} against a local SQLite file (or in-memory database for tests).
 * There is intentionally no {@code @Transactional} — SQLite is single-writer and all
 * saves are explicit, keeping the engine code straightforward.
 *
 * <p>The repository enforces optimistic locking: {@link #save} detects concurrent
 * modifications via the {@link com.yourco.ivr.domain.IvrSession#getVersion() version}
 * field and throws {@link com.yourco.ivr.exception.SessionConflictException} on conflict.
 * Sessions that have exceeded their TTL ({@code ivr.session.ttl-minutes}) are treated as
 * not found.
 */
public interface SessionRepository {

    /**
     * Persists the session. On first save ({@code version == 0}) it INSERTs; on subsequent
     * saves it UPDATEs with an optimistic-lock check.
     *
     * @throws com.yourco.ivr.exception.SessionConflictException if a concurrent update
     *         modified the session between the last read and this save
     */
    void save(IvrSession session);

    /**
     * Retrieves the session by ID, enforcing TTL expiry.
     *
     * @param sessionId the unique session identifier
     * @return the live session
     * @throws com.yourco.ivr.exception.SessionNotFoundException if the session does not exist
     *         or has expired
     */
    IvrSession getOrThrow(String sessionId);

    /**
     * Deletes the session record. Used when the IVR call ends ({@code DELETE /ivr/authenticate/{id}})
     * and by the TTL cleanup scheduler. No-op if the session does not exist.
     */
    void delete(String sessionId);
}