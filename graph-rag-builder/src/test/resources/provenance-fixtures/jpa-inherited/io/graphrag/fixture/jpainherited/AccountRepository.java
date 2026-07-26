package io.graphrag.fixture.jpainherited;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ProvenanceIndexerIT 픽스처 — {@code findById}를 재선언하지 않는 순정 JpaRepository 관례(실 SUT
 * order-service.AccountRepository와 동일 관례). noClasspath에서 상속 메서드는 {@code
 * getDeclaringType()}/{@code getType()}(반환 타입) 모두 해소되지 않는다.
 */
public interface AccountRepository extends JpaRepository<Account, String> {
}
