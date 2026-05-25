package io.graphrag.builder.capture.socket;

import java.util.Optional;

/**
 * Socket 바이트 스트림을 구조화된 객체로 디코드하는 SPI.
 *
 * <p>Phase 5 도입. 프로토콜 사양이 확보되면 endpoint별로 등록.
 * 미등록 endpoint의 바이트는 raw hex로 보존됨.
 *
 * <p>예: 가변 길이 바이너리 프로토콜에 대한 디코더:
 * <pre>
 * registry.register("inv.host:9000", new InventoryProtocolDecoder());
 * </pre>
 */
public interface ProtocolDecoder {

    /** 호스트:포트 등에 대해 이 디코더가 적용 가능한지. */
    boolean matches(String hostPort);

    /** 디코드. 실패 시 empty. */
    Optional<Object> decode(byte[] bytes);
}
