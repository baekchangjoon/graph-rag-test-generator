package io.graphrag.sample.orders;

import io.graphrag.sample.orders.auth.JwtUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil();

    @Test
    void generate_then_validate() {
        String token = jwtUtil.generate("admin");
        assertThat(token).isNotBlank();
        assertThat(jwtUtil.validate(token)).isTrue();
        assertThat(jwtUtil.getUsername(token)).isEqualTo("admin");
    }

    @Test
    void invalidToken_returnsFalse() {
        assertThat(jwtUtil.validate("invalid.token.here")).isFalse();
    }
}
