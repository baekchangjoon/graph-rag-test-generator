package io.graphrag.sample.orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRecordRepository extends JpaRepository<SkuRecord, String> {
}
