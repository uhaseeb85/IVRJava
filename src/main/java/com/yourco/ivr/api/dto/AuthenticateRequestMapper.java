package com.yourco.ivr.api.dto;

/**
 * Utility methods to convert an {@link AuthenticateRequest} into the specific
 * request DTOs for each action type.
 */
public final class AuthenticateRequestMapper {

    private AuthenticateRequestMapper() {
        // utility class
    }

    /**
     * Converts the generic request into a {@link CallTransferRequest} for the
     * TRANSFER action.
     */
    public static CallTransferRequest toTransferRequest(AuthenticateRequest req) {
        CallTransferRequest transfer = new CallTransferRequest();
        transfer.setSourceSystemId(req.getSourceSystemId());
        transfer.setBrandId(req.getBrandId());
        transfer.setCallerId(req.getCallerId());
        transfer.setCurrentLevel(req.getCurrentLevel());
        transfer.setTargetLevel(req.getTargetLevel());
        transfer.setValidatedTokens(req.getValidatedTokens());
        return transfer;
    }

    /**
     * Converts the generic request into a {@link StartAuthenticateRequest} for the
     * START action.
     */
    public static StartAuthenticateRequest toStartRequest(AuthenticateRequest req) {
        StartAuthenticateRequest start = new StartAuthenticateRequest();
        start.setBrandId(req.getBrandId());
        start.setCallerId(req.getCallerId());
        start.setTargetLevel(req.getTargetLevel());
        start.setInitialTokens(req.getInitialTokens());
        return start;
    }
}