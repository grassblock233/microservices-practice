package com.zjgsu.lht.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;

@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
    private static final String USER_ID = "X-User-Id";
    private static final String USERNAME = "X-Username";
    private static final String ROLE = "X-User-Role";
    private static final String STUDENT_ID = "X-Student-Id";
    private static final List<String> IDENTITY_HEADERS = List.of(USER_ID, USERNAME, ROLE, STUDENT_ID);
    private final JwtParser parser;

    public AuthenticationFilter(@Value("${JWT_SECRET}") String secret) {
        byte[] decoded = Base64.getDecoder().decode(secret);
        if (decoded.length < 64) throw new IllegalArgumentException("JWT_SECRET must decode to at least 64 bytes");
        parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(decoded)).build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var clean = request.mutate().headers(headers -> IDENTITY_HEADERS.forEach(headers::remove));
        Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if ("login".equals(route.getId()) && HttpMethod.POST.equals(request.getMethod())
                && "/api/auth/login".equals(request.getPath().value())) {
            return chain.filter(exchange.mutate().request(clean.build()).build());
        }

        List<String> authorization = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);
        if (authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        String userId;
        String username;
        String role;
        String studentId;
        try {
            Claims claims = parser.parseSignedClaims(authorization.getFirst().substring(7)).getPayload();
            userId = claims.getSubject();
            username = claims.get("username", String.class);
            role = claims.get("role", String.class);
            studentId = claims.get("studentId", String.class);
            if (!safeIdentifier(userId) || !safeIdentifier(username) || claims.getExpiration() == null
                    || !safeIdentifier(role) || (studentId != null && !safeIdentifier(studentId))) {
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }
        } catch (JwtException | IllegalArgumentException ex) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        boolean student = "STUDENT".equals(role);
        boolean admin = "ADMIN".equals(role);
        if ((!student && !admin)
                || ("orders".equals(route.getId()) && HttpMethod.POST.equals(request.getMethod()) && !student)
                || ("users".equals(route.getId()) && student
                    && !userId.equals(ServerWebExchangeUtils.getUriTemplateVariables(exchange).get("userId")))) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        clean.headers(headers -> {
            headers.set(USER_ID, userId);
            headers.set(USERNAME, username);
            headers.set(ROLE, role);
            if (studentId != null) headers.set(STUDENT_ID, studentId);
        });
        return chain.filter(exchange.mutate().request(clean.build()).build());
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        if (status == HttpStatus.UNAUTHORIZED) exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() { return -100; }
}
