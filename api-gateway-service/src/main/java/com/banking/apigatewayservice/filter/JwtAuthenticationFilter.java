package com.banking.apigatewayservice.filter;

import com.banking.apigatewayservice.security.GatewayJwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central authentication + authorization gate for every service behind
 * this Gateway. This is the ONLY place JWTs are validated in the system -
 * downstream services (account-service, transaction-service, ...) trust
 * the X-User-Id / X-User-Email / X-User-Role headers this filter injects,
 * and must never be reachable directly from outside the network.
 *
 * Flow:
 *   1. PUBLIC route (register/login)      -> pass through untouched
 *   2. Missing/invalid/expired token      -> 401
 *   3. Valid token, but route is          -> 403
 *      ADMIN-only and role != ADMIN
 *   4. Valid token                        -> forward with identity headers
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final GatewayJwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // method + path pairs that never require a Bearer token.
    // There is no /register route - accounts are only ever created by an
    // already-authenticated ADMIN via POST /api/v1/auth/admin/users.
    // /refresh and /logout are also public here because they authenticate
    // via the HttpOnly refresh-token cookie instead (verified inside
    // auth-service itself, not by this filter).
    private static final List<Route> PUBLIC_ROUTES = List.of(
            new Route(HttpMethod.POST, "/api/v1/auth/login"),
            new Route(HttpMethod.POST, "/api/v1/auth/refresh"),
            new Route(HttpMethod.POST, "/api/v1/auth/logout"),
            // Called server-to-server by Razorpay itself - it has no JWT.
            // Razorpay's own webhook signature verification is the real
            // security boundary here, not this Gateway's auth check.
            new Route(HttpMethod.POST, "/api/v1/payments/webhook")
    );

    // method + path patterns that require an authenticated ADMIN
    private static final List<Route> ADMIN_ONLY_ROUTES = List.of(
            new Route(HttpMethod.POST, "/api/v1/accounts"),          // create account
            new Route(HttpMethod.GET, "/api/v1/accounts"),           // list all accounts
            new Route(HttpMethod.PUT, "/api/v1/accounts/*/block"),   // block any account
            new Route(HttpMethod.GET, "/api/v1/transactions"),       // list all transactions
            new Route(HttpMethod.GET, "/api/v1/transactions/account/*"), // arbitrary account's history
            new Route(HttpMethod.POST, "/api/v1/auth/admin/**"),
            new Route(HttpMethod.GET, "/api/v1/auth/admin/**")
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getURI().getPath();

        if (matchesAny(PUBLIC_ROUTES, method, path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        String token = authHeader.substring(7).trim();

        Claims claims;
        try {
            claims = jwtService.validateAndParse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            return unauthorized(exchange, "Invalid or expired token");
        }

        String userId = claims.getSubject();
        String email = claims.get("email", String.class);
        String role = claims.get("role", String.class);

        if (matchesAny(ADMIN_ONLY_ROUTES, method, path) && !"ADMIN".equalsIgnoreCase(role)) {
            return forbidden(exchange, "This action requires ADMIN privileges");
        }

        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", userId)
                .header("X-User-Email", email == null ? "" : email)
                .header("X-User-Role", role == null ? "" : role)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // run before routing filters
        return -1;
    }

    private boolean matchesAny(List<Route> routes, HttpMethod method, String path) {
        for (Route route : routes) {
            if (route.method == method && pathMatcher.match(route.pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return writeError(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return writeError(exchange, HttpStatus.FORBIDDEN, message);
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private record Route(HttpMethod method, String pattern) {
    }
}
