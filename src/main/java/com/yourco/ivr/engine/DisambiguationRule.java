package com.yourco.ivr.engine;

import com.yourco.ivr.domain.Party;

import java.util.List;

/**
 * Strategy interface for pre-filtering the candidate party list before token-based
 * disambiguation begins.
 *
 * <p>Rules are applied in order by {@link DisambiguationEngine#applyRules} using the engine's
 * fixed rule chain. A rule receives the current list and returns a (possibly smaller) filtered
 * list. Returning an empty list will cause the session to fail.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link impl.ExcludeInactiveRule} — removes parties where {@code active == false}</li>
 *   <li>{@link impl.PrimaryAniRule} — prefers parties whose {@code primaryAni == true}, falling
 *       back to all parties if none are flagged</li>
 * </ul>
 *
 * <p>The active rule chain is fixed in the {@link DisambiguationEngine} constructor — add a new
 * implementation there to register a custom rule.
 */
public interface DisambiguationRule {
    /**
     * Filters or reorders the candidate party list.
     *
     * @param parties the current candidate list; must not be modified in place — return a new list
     * @return the filtered list; may be empty (which will fail disambiguation)
     */
    List<Party> apply(List<Party> parties);
}
