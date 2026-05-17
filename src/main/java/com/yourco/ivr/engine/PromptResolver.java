package com.yourco.ivr.engine;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.TokenPath;
import org.springframework.stereotype.Component;

@Component
public class PromptResolver {

    /**
     * Generate a human-readable IVR prompt for collecting the next token.
     */
    public String resolvePrompt(TokenType tokenType, TokenPath activePath, int remainingAttempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please provide your ");

        switch (tokenType) {
            case ACCOUNT_NUMBER:
                sb.append("account number");
                break;
            case PIN:
                sb.append("PIN");
                break;
            case OTP:
                sb.append("one-time passcode");
                break;
            case SSN_LAST4:
                sb.append("last 4 digits of your SSN");
                break;
            case VOICE_PRINT:
                sb.append("voice verification");
                break;
            case DATE_OF_BIRTH:
                sb.append("date of birth");
                break;
            case CARD_LAST4:
                sb.append("last 4 digits of your card");
                break;
            default:
                sb.append("authentication token");
                break;
        }

        if (activePath != null && activePath.getDescription() != null) {
            sb.append(" (").append(activePath.getDescription()).append(")");
        }

        if (remainingAttempts > 0) {
            sb.append(". You have ").append(remainingAttempts).append(" attempt(s) remaining");
        }

        sb.append(".");
        return sb.toString();
    }
}