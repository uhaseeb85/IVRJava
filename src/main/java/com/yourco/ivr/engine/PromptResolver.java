package com.yourco.ivr.engine;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.TokenPath;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates human-readable IVR prompt strings for token collection steps.
 *
 * <p>Called by {@link AuthEngine#evaluateProgress} and the engine's failure handlers to
 * produce the {@code prompt} field in {@link com.yourco.ivr.api.dto.AuthenticateResponse}.
 * The prompt names the required token, lists any backup alternatives, and states how many
 * attempts remain.
 *
 * <p>Prompt text is English-only. For multi-language support, extend this class to accept
 * a locale and branch in {@link #tokenName}.
 */
@Component
public class PromptResolver {

    /**
     * Builds a prompt string for the given token type and path context.
     *
     * @param tokenType        the primary token the caller should provide
     * @param activePath       the current token path (used to look up backup alternatives and description)
     * @param remainingAttempts number of attempts left; appended to the prompt when {@code > 0}
     * @return a caller-facing instruction string suitable for TTS or screen display
     */
    public String resolvePrompt(TokenType tokenType, TokenPath activePath, int remainingAttempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please provide your ").append(tokenName(tokenType));

        if (activePath != null && activePath.getDescription() != null) {
            sb.append(" (").append(activePath.getDescription()).append(")");
        }

        appendAlternatives(sb, tokenType, activePath);

        if (remainingAttempts > 0) {
            sb.append(". You have ").append(remainingAttempts).append(" attempt(s) remaining");
        }

        sb.append(".");
        return sb.toString();
    }

    /** Appends a human-readable list of backup alternatives to {@code sb}, if any exist. */
    private void appendAlternatives(StringBuilder sb, TokenType tokenType, TokenPath activePath) {
        if (activePath == null || activePath.getBackupTokens() == null) {
            return;
        }
        List<TokenType> alternatives = activePath.getBackupTokens().get(tokenType);
        if (alternatives == null || alternatives.isEmpty()) {
            return;
        }
        sb.append(". You may also provide ");
        for (int i = 0; i < alternatives.size(); i++) {
            if (i > 0) {
                sb.append(i == alternatives.size() - 1 ? " or " : ", ");
            }
            sb.append("your ").append(tokenName(alternatives.get(i)));
        }
        sb.append(" instead");
    }

    private String tokenName(TokenType tokenType) {
        return tokenType.getDisplayName();
    }
}