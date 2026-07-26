package io.graphrag.fixture.jpaoverride;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** ProvenanceIndexerIT 픽스처 — JpaRepository 상속 인터페이스(repository 인식 대상, REQ-004). */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findById(Long id);
}
