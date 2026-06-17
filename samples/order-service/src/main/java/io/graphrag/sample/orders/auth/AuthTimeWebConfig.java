package io.graphrag.sample.orders.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** AuthTimeInterceptor를 /api/** 에 등록. 인터셉터는 REQUIRE_AUTH_TIME=true 일 때만 동작(기본 비활성). */
@Configuration
public class AuthTimeWebConfig implements WebMvcConfigurer {

    private final AuthTimeInterceptor authTimeInterceptor;

    public AuthTimeWebConfig(AuthTimeInterceptor authTimeInterceptor) {
        this.authTimeInterceptor = authTimeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 로그인은 pre-auth bootstrap(SecurityConfig에서 permitAll) → freshness 게이트 제외.
        // 게이트는 인증된 비즈니스 엔드포인트만 보호한다.
        registry.addInterceptor(authTimeInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
