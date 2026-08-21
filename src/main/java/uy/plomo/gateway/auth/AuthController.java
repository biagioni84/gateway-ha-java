package uy.plomo.gateway.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    @Value("${gateway.auth.username:admin}")
    private String username;

    @Value("${gateway.auth.password:changeme}")
    private String password;

    @Value("${gateway.auth.jwt.expiry.hours:24}")
    private int expiryHours;

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String u = body.getOrDefault("username", "");
        String p = body.getOrDefault("password", "");

        if (!username.equals(u) || !password.equals(p)) {
            log.warn("Auth: failed login attempt for user '{}'", u);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid credentials"));
        }

        String token = jwtService.generate(u);
        log.info("Auth: login successful for user '{}'", u);
        return ResponseEntity.ok(Map.of(
                "token",     token,
                "expiresIn", expiryHours * 3600
        ));
    }
}
