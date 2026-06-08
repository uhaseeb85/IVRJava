package com.yourco.ivr.domain;

/**
 * Lifecycle state of an {@link IvrSession}.
 *
 * <p>The engine ({@link com.yourco.ivr.engine.AuthEngine}) transitions between these states
 * as the caller submits tokens. Terminal states (AUTHENTICATED, REDIRECT_TO_AGENT, EXPIRED, FAILED)
 * do not advance further.
 */
public enum SessionStatus {
    /** The session is actively collecting the next required token from the caller. */
    COLLECTING,
    /**
     * Reserved for future use — intended for asynchronous validation workflows where the
     * backend call is in-flight. Currently not set by the engine.
     */
    VALIDATING,
    /** All required tokens for the target level have been validated. Terminal state. */
    AUTHENTICATED,
    /**
     * The caller exceeded the maximum retry count. The session is redirected to a live agent
     * until {@link IvrSession#getLockedUntil()} expires. Terminal until the delay lapses,
     * after which the engine auto-resets on the next token submission.
     */
    REDIRECT_TO_AGENT,
    /** The session exceeded its TTL ({@code ivr.session.ttl-minutes}) and was deleted. */
    EXPIRED,
    /** All token paths were exhausted or disambiguation failed. Terminal state. */
    FAILED
}
