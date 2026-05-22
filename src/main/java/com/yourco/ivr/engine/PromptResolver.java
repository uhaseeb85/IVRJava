package com.yourco.ivr.engine;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.TokenPath;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptResolver {

    /**
     * Generate a human-readable IVR prompt for collecting the next token.
     */
    public String resolvePrompt(TokenType tokenType, TokenPath activePath, int remainingAttempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please provide your ");
        sb.append(tokenName(tokenType));

        if (activePath != null && activePath.getDescription() != null) {
            sb.append(" (").append(activePath.getDescription()).append(")");
        }

        // Mention backup alternatives if this token type has them
        if (activePath != null && activePath.getBackupTokens() != null) {
            List<TokenType> alternatives = activePath.getBackupTokens().get(tokenType);
            if (alternatives != null && !alternatives.isEmpty()) {
                sb.append(". You may also provide ");
                for (int i = 0; i < alternatives.size(); i++) {
                    if (i > 0 && i == alternatives.size() - 1) {
                        sb.append(" or ");
                    } else if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append("your ").append(tokenName(alternatives.get(i)));
                }
                sb.append(" instead");
            }
        }

        if (remainingAttempts > 0) {
            sb.append(". You have ").append(remainingAttempts).append(" attempt(s) remaining");
        }

        sb.append(".");
        return sb.toString();
    }

    private String tokenName(TokenType tokenType) {
        switch (tokenType) {
            case ACCOUNT_NUMBER: return "account number";
            case PIN:            return "PIN";
            case OTP:            return "one-time passcode";
            case SSN_LAST4:      return "last 4 digits of your SSN";
            case VOICE_PRINT:    return "voice verification";
            case DATE_OF_BIRTH:  return "date of birth";
            case CARD_LAST4:     return "last 4 digits of your card";
            default:             return "authentication token";
        }
    }
}