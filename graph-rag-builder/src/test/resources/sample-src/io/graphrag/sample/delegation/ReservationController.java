package io.graphrag.sample.delegation;

import java.util.List;

/**
 * 컨트롤러 픽스처 — 서비스 계층에 위임하는 핸들러.
 * listReservations 핸들러가 service.list(minNights)를 1-hop으로 호출한다.
 * reachableMethods(srcDir, "...ReservationController", "listReservations") 결과에는
 *   - (ReservationController FQN, "listReservations") — 핸들러 자신
 *   - (ReservationService FQN, "list") — 1-hop 호출
 * 가 포함되어야 한다.
 */
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    public List<String> listReservations(int minNights) {
        return service.list(minNights);
    }

    /**
     * 람다/스트림 내부 invocation 포함 확인용.
     * stream().filter() 람다 안에서 service.matches()를 호출한다.
     * reachableMethods 결과에 (ReservationService, "matches")가 포함되어야 한다.
     */
    public List<String> listFiltered(int minNights) {
        return service.list(minNights).stream()
                .filter(r -> service.matches(r, minNights))
                .toList();
    }
}
