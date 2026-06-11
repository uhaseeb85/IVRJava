package com.yourco.ivr.engine.slot;

import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.TokenPath;
import com.yourco.ivr.engine.path.ActivePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Resolves and manages the mapping between submitted tokens and their required-token slots.
 */
public class TokenSlotResolver {

    private final BiFunction<IvrSession, BrandAuthConfig, ActivePath> activePathResolver;

    public TokenSlotResolver(BiFunction<IvrSession, BrandAuthConfig, ActivePath> activePathResolver) {
        this.activePathResolver = activePathResolver;
    }

    /**
     * Resolves a submitted token to the required slot it satisfies as a backup, skipping slots
     * already validated. Returns {@code submittedType} unchanged if it is not a backup for any
     * unfilled slot.
     */
    public TokenType resolveBackupToken(IvrSession session, BrandAuthConfig config, TokenType submittedType) {
        TokenPath activePath = activePathResolver.apply(session, config).path();
        return findSlotForBackup(activePath, submittedType, session.getValidatedTokens());
    }

    /**
     * Resolves a submitted token to the required slot it backs, regardless of whether that slot is
     * already validated. Returns {@code submittedType} unchanged if it backs no slot.
     */
    public TokenType findRequiredTokenForSlot(IvrSession session,
                                                BrandAuthConfig config,
                                                TokenType submittedType) {
        TokenPath activePath = activePathResolver.apply(session, config).path();
        return findSlotForBackup(activePath, submittedType, null);
    }

    /**
     * Finds the required-token slot for which {@code submittedType} is configured as a backup.
     * When {@code validatedToSkip} is non-null, slots it contains are ignored (already filled).
     * Returns {@code submittedType} when no matching slot exists.
     */
    private static TokenType findSlotForBackup(TokenPath activePath, TokenType submittedType,
                                               Set<TokenType> validatedToSkip) {
        if (activePath == null || activePath.getBackupTokens() == null) {
            return submittedType;
        }
        for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
            TokenType required = entry.getKey();
            if (validatedToSkip != null && validatedToSkip.contains(required)) {
                continue;
            }
            if (entry.getValue().contains(submittedType)) {
                return required;
            }
        }
        return submittedType;
    }

    public List<TokenType> buildAcceptedTokens(IvrSession session, TokenPath activePath,
                                                TokenType nextToken, TokenType originalRequiredToken,
                                                Predicate<TokenType> isBlocked) {
        List<TokenType> accepted = new ArrayList<>();
        accepted.add(nextToken);
        if (activePath.getBackupTokens() != null) {
            List<TokenType> backups = activePath.getBackupTokens().get(originalRequiredToken);
            if (backups != null) {
                for (TokenType backup : backups) {
                    if (!accepted.contains(backup) && !isBlocked.test(backup)) {
                        accepted.add(backup);
                    }
                }
            }
        }
        return accepted;
    }

    public static TokenType findNextRequired(Set<TokenType> validated, TokenPath path) {
        for (TokenType req : path.getRequiredTokens()) {
            if (!validated.contains(req)) {
                return req;
            }
        }
        return null;
    }
}