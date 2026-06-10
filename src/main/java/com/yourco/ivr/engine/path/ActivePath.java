package com.yourco.ivr.engine.path;

import com.yourco.ivr.domain.config.LevelRule;
import com.yourco.ivr.domain.config.TokenPath;

/**
 * Snapshot of the path the engine is currently collecting against for a session's
 * target level: the level's {@link LevelRule}, the active path index, and the
 * {@link TokenPath} at that index.
 *
 * <p>Either reference may be {@code null}: {@code rule} is {@code null} when the brand
 * defines no rule for the target level, and {@code path} is {@code null} when the active
 * index points past the last path (i.e. all fallback paths are exhausted).
 */
public final class ActivePath {
    private final LevelRule rule;
    private final int index;
    private final TokenPath path;

    public ActivePath(LevelRule rule, int index, TokenPath path) {
        this.rule = rule;
        this.index = index;
        this.path = path;
    }

    public LevelRule rule() { return rule; }
    public int index() { return index; }
    public TokenPath path() { return path; }
}