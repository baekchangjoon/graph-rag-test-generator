package io.graphrag.fixture.impldispatch;

import org.springframework.stereotype.Component;

/** SocialVerifier 구현 2/2. */
@Component
public class RealSocialVerifier implements SocialVerifier {

    @Override
    public String verify(String provider, String providerToken) {
        return "OK";
    }
}
