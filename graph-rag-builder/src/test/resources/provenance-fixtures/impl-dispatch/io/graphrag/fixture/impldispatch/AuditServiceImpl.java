package io.graphrag.fixture.impldispatch;

import org.springframework.stereotype.Service;

/**
 * AuditService의 유일한 구현 — interface + Impl은 Spring에서 가장 흔한 구성이다.
 * 가드가 여기 있으므로 인터페이스에서 멈추면 통째로 놓친다.
 */
@Service
public class AuditServiceImpl implements AuditService {

    @Override
    public String record(String provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        return "OK";
    }
}
