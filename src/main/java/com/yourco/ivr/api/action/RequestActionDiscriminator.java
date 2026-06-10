package com.yourco.ivr.api.action;

import com.yourco.ivr.api.dto.AuthenticateRequest;

/**
 * Classifies an {@link AuthenticateRequest} into one of the four supported
 * {@link RequestAction} values based on which fields are present or absent.
 *
 * <p>The discrimination rules are:
 * <ul>
 *   <li>{@code sessionId == null} AND {@code sourceSystemId == null} → {@link RequestAction#START}</li>
 *   <li>{@code sessionId == null} AND {@code sourceSystemId != null} → {@link RequestAction#TRANSFER}</li>
 *   <li>{@code sessionId != null} AND {@code tokenType != null} → {@link RequestAction#SUBMIT_TOKEN}</li>
 *   <li>{@code sessionId != null} AND {@code tokenType == null} → {@link RequestAction#ESCALATE}
 *       ({@code targetLevel} must be present)</li>
 * </ul>
 */
public final class RequestActionDiscriminator {

    private RequestActionDiscriminator() {
        // utility class
    }

    /**
     * Classifies the given request into a {@link RequestAction}.
     *
     * @param req the incoming authenticate request
     * @return the resolved action type
     * @throws IllegalArgumentException if the request fields do not match any action
     *         (e.g. sessionId present with no tokenType and no targetLevel)
     */
    public static RequestAction classify(AuthenticateRequest req) {
        if (req.getSessionId() == null) {
            return req.getSourceSystemId() != null ? RequestAction.TRANSFER : RequestAction.START;
        }
        if (req.getTokenType() != null) {
            return RequestAction.SUBMIT_TOKEN;
        }
        if (req.getTargetLevel() == null) {
            throw new IllegalArgumentException(
                "Escalate action requires targetLevel when sessionId is present and tokenType is absent");
        }
        return RequestAction.ESCALATE;
    }
}