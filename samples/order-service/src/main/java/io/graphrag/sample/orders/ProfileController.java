package io.graphrag.sample.orders;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시드 타깃 해석(SQL-기반 2-pass) 회귀 가드.
 * REST 리소스명 "profiles"가 테이블명 "users"와 다르고, 비-PK 컬럼 name 으로 조회한다.
 * → path-string 휴리스틱은 테이블을 해석하지 못한다(빈 DB면 seed 0 → 빈 결과).
 * → 빌더는 캡처한 SELECT(from users where name=?)의 FROM/WHERE로 users/name 을 시드해야
 *   read-path 테스트가 실제 데이터를 받는다(analytics getUserMood 류 재현).
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final UserRepository users;

    public ProfileController(UserRepository users) {
        this.users = users;
    }

    public record ProfileResponse(String id, String name) {
    }

    @GetMapping("/by-name/{name}")
    public List<ProfileResponse> byName(@PathVariable String name) {
        return users.findByName(name).stream()
                .map(u -> new ProfileResponse(u.getId(), u.getName()))
                .toList();
    }
}
