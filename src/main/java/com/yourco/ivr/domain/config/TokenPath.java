package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TokenPath {
    private int pathIndex;
    private String description;
    private List<TokenType> requiredTokens;

    /**
     * Optional mapping from a required token to alternative tokens that can
     * satisfy it. Example: "SSN_LAST4" -> ["ACCOUNT_NUMBER"] means if the
     * client provides ACCOUNT_NUMBER when SSN_LAST4 is required, it will be
     * accepted as a substitute. This does NOT change which tokens are required
     * per the config — it only allows an alternative token to be validated
     * in place of the originally required one.
     */
    private Map<TokenType, List<TokenType>> backupTokens;
}