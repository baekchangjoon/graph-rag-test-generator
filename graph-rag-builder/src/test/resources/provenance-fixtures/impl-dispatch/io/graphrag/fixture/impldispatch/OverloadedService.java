package io.graphrag.fixture.impldispatch;

import org.springframework.stereotype.Service;

/**
 * 동명 오버로드 — 이름만으로 첫 매치를 고르면 가드 없는 쪽을 분석하고 visited에 박아,
 * 진짜 가드를 가진 오버로드를 영원히 방문하지 못한다.
 */
@Service
public class OverloadedService {

    /** 가드 없음. */
    public String lookup(String id) {
        return "OK";
    }

    /** 가드 있음 — 이쪽이 실제 호출 대상이다. */
    public String lookup(String id, boolean deep) {
        if (id == null) {
            throw new IllegalArgumentException("id required");
        }
        return "OK:" + deep;
    }
}
