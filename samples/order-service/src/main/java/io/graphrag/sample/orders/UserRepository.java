package io.graphrag.sample.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    // 비-PK 컬럼(name) 파생 조회 — 시드 타깃 해석 회귀 가드(ProfileController)에서 사용.
    List<User> findByName(String name);
}
