package com.banking.apigatewayservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies JWTs issued by auth-service. The secret configured here
 * (jwt.secret) MUST be identical to auth-service's jwt.secret - this
 * Gateway is the ONLY place tokens are validated in this system.
 */
@Service
public class GatewayJwtService {

    private final SecretKey signingKey;

    public GatewayJwtService(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the parsed claims if the token is valid (correct signature,
     * not expired). Throws JwtException (or a subclass) otherwise.
     */
    public Claims validateAndParse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
