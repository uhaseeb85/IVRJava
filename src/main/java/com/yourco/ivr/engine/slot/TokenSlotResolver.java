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

    public TokenType resolveBackupToken(IvrSession session, BrandAuthConfig config, TokenType submittedType) {
        ActivePath ap = activePathResolver.apply(session, config);
        TokenPath activePath = ap.path();
        if (activePath == null) return submittedType;
        if (activePath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
                TokenType required = entry.getKey();
                List<TokenType> backups = entry.getValue();
                if (!session.getValidatedTokens().contains(required) && backups.contains(submittedType)) {
                    return required;
                }
            }
        }
        return submittedType;
    }

    public TokenType findRequiredTokenForSlot(IvrSession session,
                                                BrandAuthConfig config,
                                                TokenType submittedType) {
        ActivePath ap = activePathResolver.apply(session, config);
        TokenPath activePath = ap.path();
        if (activePath == null) return submittedType;
        if (activePath.getBackupTokens() != null) {
            for (Map.Entry<TokenType, List<TokenType>> entry : activePath.getBackupTokens().entrySet()) {
                if (entry.getValue().contains(submittedType)) {
                    return entry.getKey();
                }
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