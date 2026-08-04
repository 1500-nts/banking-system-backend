package com.banking.authservice.controller;

import com.banking.authservice.dto.*;
import com.banking.authservice.exception.ApiException;
import com.banking.authservice.security.RefreshCookieFactory;
import com.banking.authservice.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    /*
     * PUBLIC endpoints - the Gateway allows these through without a
     * Bearer token.
     *
     * There is intentionally NO self-registration endpoint. Every account
     * (USER or ADMIN) is created by an already-logged-in ADMIN via
     * POST /admin/users below. The very first admin comes from
     * AdminBootstrapRunner, seeded from ADMIN_BOOTSTRAP_EMAIL /
     * ADMIN_BOOTSTRAP_PASSWORD.
     */

    // Issues an access token (JSON body) + refresh token (HttpOnly cookie).
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return withRefreshCookie(result);
    }

    // Silent, stateless session renewal: the browser sends the HttpOnly
    // refresh cookie automatically; no Authorization header is used or
    // needed here. Issues a brand new access + refresh token pair.
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String refreshToken = extractRefreshCookie(request);
        AuthService.AuthResult result = authService.refresh(refreshToken);
        return withRefreshCookie(result);
    }

    // Clears the refresh cookie. Since tokens are stateless (never stored
    // server-side), there is nothing else to invalidate - once the cookie
    // is gone the browser simply has no way to obtain new access tokens.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cleared = refreshCookieFactory.clear();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthService.AuthResult result) {
        ResponseCookie cookie = refreshCookieFactory.build(result.refreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result.body());
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "No refresh token cookie present");
        }
        return java.util.Arrays.stream(request.getCookies())
                .filter(c -> RefreshCookieFactory.COOKIE_NAME.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "No refresh token cookie present"));
    }

    /*
     * AUTHENTICATED endpoints - the Gateway has already validated the JWT
     * and forwards the caller's identity via these headers:
     *   X-User-Id, X-User-Email, X-User-Role
     */

    // Any logged-in user can see their own profile
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(authService.getCurrentUser(userId));
    }

    /*
     * ADMIN-ONLY endpoints.
     * The Gateway already blocks non-admins from reaching these routes,
     * but we re-check X-User-Role here too (defense in depth - never trust
     * a single layer of enforcement).
     */

    // Admin creates a user with an explicit role (USER or ADMIN)
    @PostMapping("/admin/users")
    public ResponseEntity<UserResponse> createUser(
            @RequestHeader("X-User-Role") String requesterRole,
            @Valid @RequestBody AdminCreateUserRequest request) {
        requireAdmin(requesterRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.createUserByAdmin(request));
    }

    // Admin lists every user in the system
    @GetMapping("/admin/users")
    public ResponseEntity<List<UserResponse>> getAllUsers(
            @RequestHeader("X-User-Role") String requesterRole) {
        requireAdmin(requesterRole);
        return ResponseEntity.ok(authService.getAllUsers());
    }

    // Admin looks up any single user by id
    @GetMapping("/admin/users/{userId}")
    public ResponseEntity<UserResponse> getUserById(
            @RequestHeader("X-User-Role") String requesterRole,
            @PathVariable String userId) {
        requireAdmin(requesterRole);
        return ResponseEntity.ok(authService.getUserById(userId));
    }

    private void requireAdmin(String requesterRole) {
        if (!"ADMIN".equalsIgnoreCase(requesterRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This action requires ADMIN privileges");
        }
    }
}
