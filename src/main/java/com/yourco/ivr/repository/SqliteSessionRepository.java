package com.yourco.ivr.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.CustomerPreference;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.SessionPhase;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.exception.SessionConflictException;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.exception.SessionSerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SQLite-backed implementation of {@link SessionRepository} using Spring's {@link JdbcTemplate}.
 *
 * <p>Session state is stored in the {@code ivr_session} table (schema defined in
 * {@code src/main/resources/schema.sql}). Complex fields ({@link com.yourco.ivr.domain.IvrSession}
 * collections and objects) are serialised to JSON columns via Jackson.
 *
 * <p><strong>Sensitive data:</strong> {@code collected_tokens} (raw PIN, SSN, etc.) is
 * intentionally never written to the database — the column is always stored as {@code null}.
 * Only the set of successfully <em>validated</em> token types is persisted.
 *
 * <p><strong>Optimistic locking:</strong> the {@code version} column is incremented on every
 * {@code UPDATE}. If the row's version no longer matches the in-memory version a
 * {@link com.yourco.ivr.exception.SessionConflictException} is thrown.
 *
 * <p><strong>TTL cleanup:</strong> {@link #cleanupExpired()} runs on a fixed-rate schedule
 * (default 60 seconds, configurable via {@code ivr.session.cleanup.interval}) and bulk-deletes
 * sessions older than {@code ivr.session.ttl-minutes} (default 30 minutes). {@link #getOrThrow}
 * also performs a per-row TTL check on every read.
 */
@Repository
public class SqliteSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration sessionTtl;

    public SqliteSessionRepository(JdbcTemplate jdbc,
                                    ObjectMapper mapper,
                                    @Value("${ivr.session.ttl-minutes:30}") int ttlMinutes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Persists the session. Dispatches to {@link #insert} for new sessions ({@code version == 0})
     * and {@link #update} for existing ones. Always stamps {@code lastActivityAt} before writing.
     */
    @Override
    public void save(IvrSession session) {
        session.setLastActivityAt(Instant.now());
        if (session.getVersion() == 0) {
            insert(session);
        } else {
            update(session);
        }
    }

    private void insert(IvrSession session) {
        int version = 1;
        session.setVersion(version);
        String sql = "INSERT INTO ivr_session " +
            "(session_id, brand_id, caller_id, current_level, target_level, status, " +
            "phase, collected_tokens, validated_tokens, attempt_counts, active_path_index, " +
            "candidate_parties, matched_party, customer_preferences, " +
            "disambiguation_attempt, version, transferred_from, locked_until, created_at, " +
            "last_activity_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // Column order: session_id, <shared payload>, created_at, last_activity_at
        List<Object> params = new ArrayList<>();
        params.add(session.getSessionId());
        params.addAll(sharedColumnValues(session, version));
        params.add(toIso(session.getCreatedAt()));
        params.add(toIso(session.getLastActivityAt()));

        jdbc.update(sql, params.toArray());
    }

    private void update(IvrSession session) {
        int expectedVersion = session.getVersion();
        int newVersion = expectedVersion + 1;
        session.setVersion(newVersion);
        String sql = "UPDATE ivr_session SET " +
            "brand_id = ?, caller_id = ?, current_level = ?, target_level = ?, status = ?, " +
            "phase = ?, collected_tokens = ?, validated_tokens = ?, attempt_counts = ?, " +
            "active_path_index = ?, candidate_parties = ?, " +
            "matched_party = ?, customer_preferences = ?, disambiguation_attempt = ?, " +
            "version = ?, transferred_from = ?, locked_until = ?, last_activity_at = ? " +
            "WHERE session_id = ? AND version = ?";

        // Column order: <shared payload>, last_activity_at, then WHERE session_id, version
        List<Object> params = new ArrayList<>(sharedColumnValues(session, newVersion));
        params.add(toIso(session.getLastActivityAt()));
        params.add(session.getSessionId());
        params.add(expectedVersion);

        int rows = jdbc.update(sql, params.toArray());
        if (rows == 0) {
            throw new SessionConflictException(session.getSessionId());
        }
    }

    /**
     * The session payload columns shared by {@link #insert} and {@link #update}, in the order they
     * appear in both statements: {@code brand_id} through {@code locked_until}. Both statements wrap
     * this block with their own leading/trailing columns (session id, timestamps, version guard).
     */
    private List<Object> sharedColumnValues(IvrSession session, int version) {
        List<Object> params = new ArrayList<>();
        params.add(session.getBrandId());
        params.add(session.getCallerId());
        params.add(session.getCurrentLevel().name());
        params.add(session.getTargetLevel().name());
        params.add(session.getStatus().name());
        params.add(session.getPhase() != null ? session.getPhase().name() : SessionPhase.AUTHENTICATING.name());
        params.add(null);  // collected_tokens: never persisted — values are sensitive (PINs, SSNs)
        params.add(toJson(session.getValidatedTokens()));
        params.add(toJson(session.getAttemptCounts()));
        params.add(toJson(session.getActivePathIndexByLevel()));
        params.add(toJson(session.getCandidateParties()));
        params.add(toJson(session.getMatchedParty()));
        params.add(toJson(session.getCustomerPreferences()));
        params.add(session.getDisambiguationAttemptCount());
        params.add(version);
        params.add(session.getTransferredFrom());
        params.add(toIso(session.getLockedUntil()));
        return params;
    }

    @Override
    public IvrSession getOrThrow(String sessionId) {
        String sql = "SELECT * FROM ivr_session WHERE session_id = ?";
        try {
            IvrSession session = jdbc.queryForObject(sql, this::mapRow, sessionId);
            if (session == null) {
                throw new SessionNotFoundException(sessionId);
            }
            if (session.getLastActivityAt().plus(sessionTtl).isBefore(Instant.now())) {
                delete(sessionId);
                throw new SessionNotFoundException(sessionId);
            }
            return session;
        } catch (EmptyResultDataAccessException e) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    @Override
    public void delete(String sessionId) {
        jdbc.update("DELETE FROM ivr_session WHERE session_id = ?", sessionId);
    }

    @Override
    public List<IvrSession> listAll() {
        String sql = "SELECT * FROM ivr_session WHERE last_activity_at >= ? ORDER BY created_at DESC";
        Instant cutoff = Instant.now().minus(sessionTtl);
        try {
            return jdbc.query(sql, this::mapRow, toIso(cutoff));
        } catch (Exception e) {
            log.warn("Failed to list sessions", e);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public List<IvrSession> search(String brandId, String status, String callerId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ivr_session WHERE last_activity_at >= ?");
        List<Object> params = new ArrayList<>();
        params.add(toIso(Instant.now().minus(sessionTtl)));

        if (brandId != null && !brandId.trim().isEmpty()) {
            sql.append(" AND LOWER(brand_id) LIKE ?");
            params.add("%" + brandId.trim().toLowerCase() + "%");
        }
        if (status != null && !status.trim().isEmpty()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (callerId != null && !callerId.trim().isEmpty()) {
            sql.append(" AND caller_id LIKE ?");
            params.add("%" + callerId.trim() + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200");

        try {
            return jdbc.query(sql.toString(), this::mapRow, params.toArray());
        } catch (Exception e) {
            log.warn("Failed to search sessions", e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Bulk-deletes sessions whose {@code last_activity_at} is older than the configured TTL.
     * Runs automatically at a fixed rate (default 60 000 ms, override via
     * {@code ivr.session.cleanup.interval}).
     */
    @Scheduled(fixedRateString = "${ivr.session.cleanup.interval:60000}")
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(sessionTtl);
        int deleted = jdbc.update("DELETE FROM ivr_session WHERE last_activity_at < ?", toIso(cutoff));
        if (deleted > 0) {
            log.debug("Cleaned up {} expired session(s)", deleted);
        }
    }

    private IvrSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        IvrSession s = new IvrSession();
        s.setSessionId(rs.getString("session_id"));
        s.setBrandId(rs.getString("brand_id"));
        s.setCallerId(rs.getString("caller_id"));
        try {
            s.setCurrentLevel(AuthLevel.valueOf(rs.getString("current_level")));
            s.setTargetLevel(AuthLevel.valueOf(rs.getString("target_level")));
            s.setStatus(SessionStatus.valueOf(rs.getString("status")));
            String phaseStr = rs.getString("phase");
            s.setPhase(phaseStr != null ? SessionPhase.valueOf(phaseStr) : SessionPhase.AUTHENTICATING);
            s.setLockedUntil(fromIso(rs.getString("locked_until")));
            s.setCreatedAt(fromIso(rs.getString("created_at")));
            s.setLastActivityAt(fromIso(rs.getString("last_activity_at")));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Corrupted enum name or timestamp in the row — surface as a serialization
            // error (consistent with the JSON helpers) instead of a raw 500.
            throw new SessionSerializationException(
                "Failed to map session row " + s.getSessionId(), e);
        }
        s.setCollectedTokens(fromJsonEnumMap(rs.getString("collected_tokens"), TokenType.class, String.class));
        s.setValidatedTokens(fromJsonEnumSet(rs.getString("validated_tokens"), TokenType.class));
        s.setAttemptCounts(fromJsonEnumMap(rs.getString("attempt_counts"), TokenType.class, Integer.class));
        s.setActivePathIndexByLevel(fromJsonEnumMap(rs.getString("active_path_index"), AuthLevel.class, Integer.class));
        s.setCandidateParties(fromJsonPartyList(rs.getString("candidate_parties")));
        s.setMatchedParty(fromJsonSingle(rs.getString("matched_party"), Party.class));
        s.setCustomerPreferences(fromJsonSingle(rs.getString("customer_preferences"), CustomerPreference.class));
        s.setDisambiguationAttemptCount(rs.getInt("disambiguation_attempt"));
        s.setVersion(rs.getInt("version"));
        s.setTransferredFrom(rs.getString("transferred_from"));
        return s;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SessionSerializationException("Failed to serialize session data", e);
        }
    }

    private <K extends Enum<K>, V> Map<K, V> fromJsonEnumMap(String json, Class<K> keyType, Class<V> valueType) {
        JavaType type = mapper.getTypeFactory().constructMapType(EnumMap.class, keyType, valueType);
        return readJson(json, type, () -> new EnumMap<>(keyType));
    }

    private <T extends Enum<T>> Set<T> fromJsonEnumSet(String json, Class<T> elementType) {
        JavaType type = mapper.getTypeFactory().constructCollectionType(EnumSet.class, elementType);
        return readJson(json, type, () -> EnumSet.noneOf(elementType));
    }

    private List<Party> fromJsonPartyList(String json) {
        JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, Party.class);
        return readJson(json, type, ArrayList::new);
    }

    private <T> T fromJsonSingle(String json, Class<T> clazz) {
        return readJson(json, mapper.getTypeFactory().constructType(clazz), () -> null);
    }

    /**
     * Deserializes a JSON column into the given {@link JavaType}, returning {@code emptyValue} for a
     * null/blank column. Any Jackson failure is surfaced as a {@link SessionSerializationException}.
     */
    private <T> T readJson(String json, JavaType type, Supplier<T> emptyValue) {
        if (json == null || json.isEmpty()) return emptyValue.get();
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new SessionSerializationException("Failed to deserialize " + type, e);
        }
    }

    private static String toIso(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant fromIso(String iso) {
        return iso != null ? Instant.parse(iso) : null;
    }
}
