package io.graphrag.fixture.impldispatch;

import org.springframework.stereotype.Component;

/** SocialVerifier 구현 1/2 — 실질 가드가 여기 있다. */
@Component
public class MockSocialVerifier implements SocialVerifier {

    @Override
    public String verify(String provider, String providerToken) {
        if (!providerToken.startsWith("valid-")) {
            throw new IllegalArgumentException("invalid token");
        }
        return "OK";
    }
}
