package io.graphrag.sample.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bean Validation(@Valid @RequestBody)로 각 제약 위반을 400으로 거부하는 엔드포인트.
 * 명령형 if-throw 검증(BookingController)과 달리 선언적 어노테이션 검증의 reject arm 회귀 가드(B1).
 */
@RestController
@RequestMapping("/api/signups")
public class SignupController {

    public record SignupRequest(
            @NotBlank String name,
            @Email String email,
            @Min(18) int age,
            @Size(min = 8) String password) {
    }

    public record SignupResponse(String name, String email, int age) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest req) {
        return new SignupResponse(req.name(), req.email(), req.age());
    }
}
