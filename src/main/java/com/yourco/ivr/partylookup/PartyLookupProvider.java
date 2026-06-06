package com.yourco.ivr.partylookup;

import com.yourco.ivr.domain.Party;

import java.util.List;

/**
 * SPI for resolving a caller's ANI (Automatic Number Identification) to one or more
 * customer {@link Party} records.
 *
 * <p>Implementations query a CRM, database, or identity system. The result drives the
 * session's initial phase:
 * <ul>
 *   <li><strong>Empty list</strong> — throws {@link com.yourco.ivr.exception.UnknownCallerException};
 *       the session is rejected.</li>
 *   <li><strong>Single party</strong> — the session skips disambiguation and enters
 *       {@link com.yourco.ivr.domain.SessionPhase#AUTHENTICATING} immediately.</li>
 *   <li><strong>Multiple parties</strong> — the session enters
 *       {@link com.yourco.ivr.domain.SessionPhase#DISAMBIGUATION}; the
 *       {@link com.yourco.ivr.engine.DisambiguationEngine} collects tokens to narrow
 *       down to a unique match.</li>
 * </ul>
 *
 * <p>Replace {@link StubPartyLookupProvider} with a real implementation when integrating
 * with a CRM. Register it as a Spring {@code @Component} and it will be auto-wired.
 */
public interface PartyLookupProvider {
    /**
     * Looks up parties associated with the given caller identifier.
     *
     * @param callerId the caller's ANI or equivalent identifier
     * @return a list of matching parties; never {@code null} — return an empty list if none found
     */
    List<Party> lookupByAni(String callerId);
}
