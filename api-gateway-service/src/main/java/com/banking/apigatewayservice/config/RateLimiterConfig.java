package com.banking.apigatewayservice.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimiterConfig {

    /**
     * Keys the rate limiter by the client's IP/host.
     *
     * With server.forward-headers-strategy: framework set (application.yaml),
     * Spring rewrites getRemoteAddress() using the X-Forwarded-For header
     * from the reverse proxy (Render's edge) - but it does so by parsing the
     * header into an UNRESOLVED InetSocketAddress (no DNS lookup performed).
     * On an unresolved address, getAddress() returns null, so calling
     * .getHostAddress() on it throws a NullPointerException.
     *
     * getHostString() is the safe accessor here: it returns the address as
     * a string whether the address is resolved or not, so it works
     * identically for direct connections and forwarded ones.
     */
    @Bean
    public KeyResolver keyResolver(){
        return exchange -> {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String host = (remoteAddress != null) ? remoteAddress.getHostString() : "unknown";
            return Mono.just(host);
        };
    }
}