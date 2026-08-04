package com.banking.authservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the HttpOnly cookie that carries the refresh token.
 *
 * The cookie is scoped to /api/v1/auth so the browser only ever attaches
 * it to auth-service requests (login/refresh/logout) - never to
 * account-service or transaction-service calls.
 */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";

    private final long refreshTtlSeconds;
    private final boolean secure;
    private final String sameSite;
    private final String domain;

    public RefreshCookieFactory(
            JwtService jwtService,
            @Value("${app.cookie.secure:true}") boolean secure,
            @Value("${app.cookie.same-site:None}") String sameSite,
            @Value("${app.cookie.domain:}") String domain
    ) {
        this.refreshTtlSeconds = jwtService.getRefreshTtlSeconds();
        this.secure = secure;
        this.sameSite = sameSite;
        this.domain = domain;
    }

    public ResponseCookie build(String refreshToken) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(refreshTtlSeconds);
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        return builder.build();
    }

    /** Same name/path/attributes, but expired instantly - clears the cookie on logout. */
    public ResponseCookie clear() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(0);
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        return builder.build();
    }
}
