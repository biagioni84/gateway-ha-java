package uy.plomo.gateway.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

@Service
@Slf4j
public class JwtService {

    @Value("${gateway.auth.jwt.secret:}")
    private String configuredSecret;

    @Value("${gateway.auth.jwt.expiry.hours:24}")
    private int expiryHours;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            configuredSecret = Base64.getEncoder().encodeToString(random);
            log.info("Auth: JWT secret not configured — generated random key (tokens reset on restart)");
        }
        signingKey = Keys.hmacShaKeyFor(configuredSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(String username) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) expiryHours * 3_600_000);
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /** Returns the username if the token is valid, null otherwise. */
    public String validate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
