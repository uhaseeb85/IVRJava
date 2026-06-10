package com.yourco.ivr.engine.preference;

import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.domain.config.TokenPath;

import java.util.List;
import java.util.Set;

/**
 * Filters token types based on customer preferences (e.g. blocked tokens, max level caps).
 */
public class PreferenceFilter {

    public static boolean isBlocked(IvrSession session, TokenType tokenType) {
        if (session.getCustomerPreferences() == null) return false;
        Set<TokenType> blocked = session.getCustomerPreferences().getBlockedTokens();
        return blocked != null && blocked.contains(tokenType);
    }

    public static TokenType findAlternativeToken(IvrSession session, TokenPath path, TokenType blockedToken) {
        if (path.getBackupTokens() == null) return null;
        List<TokenType> backups = path.getBackupTokens().get(blockedToken);
        if (backups == null) return null;
        for (TokenType backup : backups) {
            if (!isBlocked(session, backup)) {
                return backup;
            }
        }
        return null;
    }
}