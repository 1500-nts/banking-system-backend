package com.banking.authservice.security;

import com.banking.authservice.entity.User;
import com.banking.authservice.exception.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies JWTs signed with shared secrets (HS256).
 *
 * Two token types, both STATELESS (nothing is persisted server-side -
 * validity is fully determined by signature + expiry + the "type" claim):
 *
 *  - ACCESS token:  short-lived, sent as "Bearer <token>" on every API
 *    call, verified by the API Gateway (jwt.secret - SAME value must be
 *    configured there).
 *  - REFRESH token: long-lived, NEVER sent to the Gateway/API routes -
 *    it only ever travels inside an HttpOnly cookie to
 *    POST /api/v1/auth/refresh, which is the one place it's verified.
 *    Signed with a separate secret so a leaked access token can never be
 *    replayed as a refresh token.
 */
@Service
public class JwtService {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey accessSigningKey;
    private final SecretKey refreshSigningKey;
    private final String issuer;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.refresh-secret}") String refreshSecret,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.access-ttl-seconds}") long accessTtlSeconds,
            @Value("${jwt.refresh-ttl-seconds}") long refreshTtlSeconds
    ) {
        // HS256 requires a key of at least 256 bits (32 bytes)
        this.accessSigningKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.refreshSigningKey = Keys.hmacShaKeyFor(refreshSecret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim("role", user.getRole().name())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(accessSigningKey)
                .compact();
    }

    /**
     * A refresh token carries only the user id (+ a "type" marker) -
     * deliberately minimal, since its only job is to prove "this browser
     * still owns a valid session" long enough to mint a fresh access token.
     */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshTtlSeconds)))
                .signWith(refreshSigningKey)
                .compact();
    }

    /**
     * Validates a refresh token's signature, expiry and type, then returns
     * the user id it was issued for. No database/session lookup - purely
     * stateless verification.
     */
    public String parseRefreshTokenSubject(String refreshToken) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(refreshSigningKey)
                    .build()
                    .parseSignedClaims(refreshToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not a refresh token");
        }
        return claims.getSubject();
    }
}
