package com.yourco.ivr.api.action;

/**
 * Discriminated action type derived from an {@link AuthenticateRequest} payload.
 *
 * <p>Each action corresponds to a distinct IVR authentication operation:
 * <ul>
 *   <li>{@link #START} — create a new authentication session</li>
 *   <li>{@link #TRANSFER} — create a session from an inbound call transfer</li>
 *   <li>{@link #SUBMIT_TOKEN} — submit a credential token for validation</li>
 *   <li>{@link #ESCALATE} — request a higher authentication level mid-session</li>
 * </ul>
 */
public enum RequestAction {
    START,
    TRANSFER,
    SUBMIT_TOKEN,
    ESCALATE
}