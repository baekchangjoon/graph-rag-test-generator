package io.graphrag.sample.delegation;

import java.util.List;

/**
 * 서비스 계층 픽스처 — 핸들러(ReservationController)가 위임하는 서비스.
 * reachableMethods 추출 시 (ReservationService FQN, "list") 쌍이 집합에 포함되어야 한다.
 */
public class ReservationService {

    public List<String> list(int minNights) {
        return List.of();
    }

    public String getById(long id) {
        return "";
    }
}
