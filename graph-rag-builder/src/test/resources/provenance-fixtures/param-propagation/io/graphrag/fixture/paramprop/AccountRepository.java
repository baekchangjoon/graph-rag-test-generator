package io.graphrag.fixture.paramprop;

import java.util.Optional;

/** ProvenanceIndexerIT 픽스처 — orElseThrow EXISTS 가드의 수신 표현식(findById) 대상. */
public interface AccountRepository {

    Optional<Account> findById(String id);
}
