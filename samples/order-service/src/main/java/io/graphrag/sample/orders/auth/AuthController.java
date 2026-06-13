package io.graphrag.sample.orders.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "password";
    private static final long EXPIRES_IN = 86400L;

    private final JwtUtil jwtUtil;

    public AuthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String type, long expiresIn) {}

    public record ErrorResponse(String error) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (!ADMIN_USER.equals(request.username()) || !ADMIN_PASS.equals(request.password())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("invalid credentials"));
        }
        String token = jwtUtil.generate(request.username());
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", EXPIRES_IN));
    }
}
