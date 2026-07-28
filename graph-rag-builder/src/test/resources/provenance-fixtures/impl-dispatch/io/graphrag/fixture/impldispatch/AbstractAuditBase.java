package io.graphrag.fixture.impldispatch;

/**
 * 추상 베이스를 낀 구현 계층 — Spring에서 흔한 구성이다. 직접 구현자만 세면 이 추상 클래스를
 * "유일 구현체"로 골라 본문 없는 메서드로 내려가 가드를 통째로 잃는다.
 */
public abstract class AbstractAuditBase implements NotifyService {

    @Override
    public abstract String notify(String channel);
}
