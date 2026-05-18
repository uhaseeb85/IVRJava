package com.yourco.ivr.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.exception.SessionSerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

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

    @Override
    public void save(IvrSession session) {
        session.setLastActivityAt(Instant.now());
        String sql = "INSERT OR REPLACE INTO ivr_session " +
            "(session_id, brand_id, caller_id, current_level, target_level, status, " +
            "collected_tokens, validated_tokens, attempt_counts, active_path_index, " +
            "cross_brand_tokens, transferred_from, locked_until, created_at, last_activity_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql,
            session.getSessionId(),
            session.getBrandId(),
            session.getCallerId(),
            session.getCurrentLevel().name(),
            session.getTargetLevel().name(),
            session.getStatus().name(),
            toJson(session.getCollectedTokens()),
            toJson(session.getValidatedTokens()),
            toJson(session.getAttemptCounts()),
            toJson(session.getActivePathIndexByLevel()),
            toJson(session.getCrossBrandTokens()),
            session.getTransferredFrom(),
            toIso(session.getLockedUntil()),
            toIso(session.getCreatedAt()),
            toIso(session.getLastActivityAt())
        );
    }

    @Override
    public IvrSession getOrThrow(String sessionId) {
        checkExpired(sessionId);

        String sql = "SELECT * FROM ivr_session WHERE session_id = ?";
        try {
            return jdbc.queryForObject(sql, this::mapRow, sessionId);
        } catch (EmptyResultDataAccessException e) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    @Override
    public void delete(String sessionId) {
        jdbc.update("DELETE FROM ivr_session WHERE session_id = ?", sessionId);
    }

    @Scheduled(fixedRateString = "${ivr.session.cleanup.interval:60000}")
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(sessionTtl);
        int deleted = jdbc.update("DELETE FROM ivr_session WHERE last_activity_at < ?", toIso(cutoff));
        if (deleted > 0) {
            log.debug("Cleaned up {} expired session(s)", deleted);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────
    private void checkExpired(String sessionId) {
        String sql = "SELECT last_activity_at FROM ivr_session WHERE session_id = ?";
        try {
            String iso = jdbc.queryForObject(sql, String.class, sessionId);
            if (iso != null) {
                Instant lastActivity = Instant.parse(iso);
                if (lastActivity.plus(sessionTtl).isBefore(Instant.now())) {
                    delete(sessionId);
                    throw new SessionNotFoundException(sessionId);
                }
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    private IvrSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        IvrSession s = new IvrSession();
        s.setSessionId(rs.getString("session_id"));
        s.setBrandId(rs.getString("brand_id"));
        s.setCallerId(rs.getString("caller_id"));
        s.setCurrentLevel(AuthLevel.valueOf(rs.getString("current_level")));
        s.setTargetLevel(AuthLevel.valueOf(rs.getString("target_level")));
        s.setStatus(SessionStatus.valueOf(rs.getString("status")));
        s.setCollectedTokens(fromJsonEnumMap(rs.getString("collected_tokens"), TokenType.class, String.class));
        s.setValidatedTokens(fromJsonEnumSet(rs.getString("validated_tokens"), TokenType.class));
        s.setAttemptCounts(fromJsonEnumMap(rs.getString("attempt_counts"), TokenType.class, Integer.class));
        s.setActivePathIndexByLevel(fromJsonEnumMap(rs.getString("active_path_index"), AuthLevel.class, Integer.class));
        s.setCrossBrandTokens(fromJsonCrossBrand(rs.getString("cross_brand_tokens")));
        s.setTransferredFrom(rs.getString("transferred_from"));
        s.setLockedUntil(fromIso(rs.getString("locked_until")));
        s.setCreatedAt(fromIso(rs.getString("created_at")));
        s.setLastActivityAt(fromIso(rs.getString("last_activity_at")));
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

    @SuppressWarnings("unchecked")
    private <K extends Enum<K>, V> Map<K, V> fromJsonEnumMap(String json, Class<K> keyType, Class<V> valueType) {
        if (json == null || json.isEmpty()) return new EnumMap<>(keyType);
        try {
            return (Map<K, V>) mapper.readValue(json,
                mapper.getTypeFactory().constructMapType(EnumMap.class, keyType, valueType));
        } catch (IOException e) {
            throw new SessionSerializationException("Failed to deserialize map", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> Set<T> fromJsonEnumSet(String json, Class<T> elementType) {
        if (json == null || json.isEmpty()) return EnumSet.noneOf(elementType);
        try {
            return (Set<T>) mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(EnumSet.class, elementType));
        } catch (IOException e) {
            throw new SessionSerializationException("Failed to deserializing enum set", e);
        }
    }

    private Map<TokenType, CrossBrandTokenRecord> fromJsonCrossBrand(String json) {
        if (json == null || json.isEmpty()) return new EnumMap<>(TokenType.class);
        try {
            return mapper.readValue(json,
                mapper.getTypeFactory().constructMapType(EnumMap.class,
                    TokenType.class, CrossBrandTokenRecord.class));
        } catch (IOException e) {
            throw new SessionSerializationException("Failed to deserialize cross-brand tokens", e);
        }
    }

    private static String toIso(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant fromIso(String iso) {
        return iso != null ? Instant.parse(iso) : null;
    }
}