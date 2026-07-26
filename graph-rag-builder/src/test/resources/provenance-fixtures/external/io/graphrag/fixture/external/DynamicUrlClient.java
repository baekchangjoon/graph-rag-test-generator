package io.graphrag.fixture.external;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ProvenanceIndexerIT 픽스처 — {@code check}가 호출하는 {@code rest.postForObject(path, ...)}의
 * URL 인자가 정적 문자열 리터럴이 아니라 메서드 파라미터(변수)이므로, callSite의 path literal 추출이
 * 실패해야 하는 케이스(REQ-003: class#method 폴백 계약 검증용). FraudClient와 달리 URL을 호출자가
 * 넘긴 변수로 구성한다 — 실무의 {@code UriComponentsBuilder}/동적 엔드포인트 라우팅과 동일한 성격.
 */
@Component
public class DynamicUrlClient {

    public record PriceResult(String status) {
    }

    private final RestTemplate rest = new RestTemplate();

    public PriceResult check(String path, String accountId) {
        return rest.postForObject(path, Map.of("accountId", accountId), PriceResult.class);
    }
}
