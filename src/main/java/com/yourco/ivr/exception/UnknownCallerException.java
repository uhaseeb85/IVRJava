package com.yourco.ivr.exception;

/**
 * Thrown when {@link com.yourco.ivr.partylookup.PartyLookupProvider#lookupByAni} returns an
 * empty list, meaning the caller's ANI does not match any known customer account.
 * Mapped to HTTP 400 by {@link com.yourco.ivr.api.IvrExceptionHandler}.
 */
public class UnknownCallerException extends RuntimeException {
    public UnknownCallerException(String callerId) {
        super("No parties found for caller: " + callerId);
    }
}
