package com.banking.authservice.service;

import com.banking.authservice.dto.*;
import com.banking.authservice.entity.Role;
import com.banking.authservice.entity.User;
import com.banking.authservice.exception.ApiException;
import com.banking.authservice.repository.UserRepository;
import com.banking.authservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /*
     * There is deliberately NO public self-registration here.
     *
     * The only way an account comes to exist is:
     *   1. AdminBootstrapRunner seeds exactly one root ADMIN on first
     *      startup (from ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD).
     *   2. That admin (or any admin created afterwards - they all carry
     *      the same ADMIN role and privileges, there is no separate
     *      "root" flag) logs in and calls createUserByAdmin() below to
     *      create further admins or account-holder users.
     */

    public AuthResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!user.isEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        log.info("User logged in: {}", user.getEmail());
        return buildAuthResult(user);
    }

    /**
     * Verifies the refresh token (stateless - signature + expiry only, no
     * server-side session store) and mints a brand new access + refresh
     * token pair for the same user. The old refresh token is simply left
     * to expire on its own; nothing needs to be revoked server-side.
     */
    public AuthResult refresh(String refreshToken) {
        String userId = jwtService.parseRefreshTokenSubject(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User no longer exists"));

        if (!user.isEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been disabled");
        }

        return buildAuthResult(user);
    }

    /**
     * Creates a user with an explicit role (including ADMIN).
     * The controller only calls this after confirming the REQUESTER
     * (via the X-User-Role header set by the Gateway) is already an ADMIN.
     */
    public UserResponse createUserByAdmin(AdminCreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setEnabled(true);

        User saved = userRepository.save(user);
        log.info("Admin created new {} account: {}", request.getRole(), saved.getEmail());

        return mapToUserResponse(saved);
    }

    public UserResponse getCurrentUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return mapToUserResponse(user);
    }

    public UserResponse getUserById(String userId) {
        return getCurrentUser(userId);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    private AuthResult buildAuthResult(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        AuthResponse body = new AuthResponse(accessToken, jwtService.getAccessTtlSeconds(), mapToUserResponse(user));
        return new AuthResult(body, refreshToken);
    }

    /**
     * Everything login()/refresh() produce: the JSON body (access token +
     * user) plus the refresh token, which the controller puts in an
     * HttpOnly cookie instead of the body.
     */
    public record AuthResult(AuthResponse body, String refreshToken) {
    }

    private UserResponse mapToUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt()
        );
    }
}
