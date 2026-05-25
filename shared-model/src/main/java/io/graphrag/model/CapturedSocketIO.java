package io.graphrag.model;

import java.util.Objects;

/**
 * 분석 중 캡처된 socket I/O 이벤트.
 *
 * @param byteHex 송수신 byte를 hex 문자열로 표현 (e.g., "01 02 03")
 * @param byteOrigin "serialized from MessageX" 등 디스크립션
 * @param protocolDecoded 디코더 등록 시 채워지는 구조화된 표현. 미등록이면 null
 * @param sessionField 격리 가능한 세션 식별자 필드명 (null if 프로토콜에 부재)
 */
public record CapturedSocketIO(
        String id,
        String pathId,
        SocketDirection direction,
        String endpointHost,
        int endpointPort,
        String byteHex,
        String byteOrigin,
        SocketProtocol protocol,
        SocketFramework framework,
        Object protocolDecoded,
        String sessionField) {

    public CapturedSocketIO {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(endpointHost, "endpointHost");
        Objects.requireNonNull(byteHex, "byteHex");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(framework, "framework");
    }
}
