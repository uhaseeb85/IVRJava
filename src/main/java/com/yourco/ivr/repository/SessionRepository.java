package com.yourco.ivr.repository;

import com.yourco.ivr.domain.IvrSession;

public interface SessionRepository {

    void save(IvrSession session);

    IvrSession getOrThrow(String sessionId);

    void delete(String sessionId);
}