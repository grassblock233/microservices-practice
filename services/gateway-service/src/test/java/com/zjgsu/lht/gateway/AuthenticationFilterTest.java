package com.zjgsu.lht.gateway;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationFilterTest {
    private static final javax.crypto.SecretKey KEY = Jwts.SIG.HS512.key().build();
    private final AuthenticationFilter filter = new AuthenticationFilter(Base64.getEncoder().encodeToString(KEY.getEncoded()));

    @Test
    void rejectsMissingInvalidExpiredAndUnexpectedTokens() {
        for (String value : new String[]{null, "Basic example", "Bearer invalid",
                bearer(claims("STUDENT").expiration(Date.from(Instant.now().minusSeconds(10)))),
                bearer(claims(null)), bearer(claims("STUDENT").claim("studentId", 42)),
                bearer(claims("STUDENT").expiration(null)),
                "Bearer " + claims("STUDENT").signWith(Jwts.SIG.HS512.key().build()).compact(),
                "Bearer " + claims("STUDENT").signWith(Jwts.SIG.HS256.key().build(), Jwts.SIG.HS256).compact()}) {
            var request = MockServerHttpRequest.get("/api/dishes");
            if (value != null) request.header(HttpHeaders.AUTHORIZATION, value);
            var exchange = exchange("dishes", request);
            filter.filter(exchange, mustNotForward()).block();
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationFilter("YQ=="));
    }

    @Test
    void preservesAuthorizationAndRebuildsOnlyVerifiedIdentityHeaders() {
        filter.filter(exchange("login", forged(MockServerHttpRequest.post("/api/auth/login"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ignored")), forwarded -> {
            var headers = forwarded.getRequest().getHeaders();
            assertEquals("Bearer ignored", headers.getFirst(HttpHeaders.AUTHORIZATION));
            for (String name : new String[]{"X-User-Id", "X-User-Role", "X-Username", "X-Student-Id"}) {
                assertFalse(headers.containsHeader(name));
            }
            return Mono.empty();
        }).block();
        String token = bearer(claims("STUDENT").claim("studentId", "campus-123"));
        filter.filter(exchange("orders", forged(MockServerHttpRequest.post("/api/orders"))
                .header(HttpHeaders.AUTHORIZATION, token)), forwarded -> {
            var headers = forwarded.getRequest().getHeaders();
            assertEquals(java.util.List.of("STUDENT"), headers.get("X-User-Role"));
            assertEquals("student-1", headers.getFirst("X-User-Id"));
            assertEquals("campus-123", headers.getFirst("X-Student-Id"));
            assertEquals("student1", headers.getFirst("X-Username"));
            assertEquals(token, headers.getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.empty();
        }).block();
    }

    @Test
    void doesNotInferStudentIdWhenClaimIsAbsent() {
        for (String role : new String[]{"STUDENT", "ADMIN"}) {
            filter.filter(exchange("dishes", forged(MockServerHttpRequest.get("/api/dishes"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(claims(role)))), forwarded -> {
                assertFalse(forwarded.getRequest().getHeaders().containsHeader("X-Student-Id"));
                return Mono.empty();
            }).block();
        }
    }

    @Test
    void blocksAdminOrderCreationAndStudentProfileImpersonation() {
        var post = exchange("orders", forged(MockServerHttpRequest.post("/api/orders"))
                .header(HttpHeaders.AUTHORIZATION, bearer(claims("ADMIN"))));
        filter.filter(post, mustNotForward()).block();
        assertEquals(HttpStatus.FORBIDDEN, post.getResponse().getStatusCode());

        var get = exchange("users", forged(MockServerHttpRequest.get("/api/users/student-2"))
                .header(HttpHeaders.AUTHORIZATION, bearer(claims("STUDENT"))));
        ServerWebExchangeUtils.putUriTemplateVariables(get, Map.of("userId", "student-2"));
        filter.filter(get, mustNotForward()).block();
        assertEquals(HttpStatus.FORBIDDEN, get.getResponse().getStatusCode());

        var unknownRole = exchange("dishes", MockServerHttpRequest.get("/api/dishes")
                .header(HttpHeaders.AUTHORIZATION, bearer(claims("OTHER"))));
        filter.filter(unknownRole, mustNotForward()).block();
        assertEquals(HttpStatus.FORBIDDEN, unknownRole.getResponse().getStatusCode());
    }

    private static MockServerHttpRequest.BaseBuilder<?> forged(MockServerHttpRequest.BaseBuilder<?> request) {
        return request.header("x-user-id", "student-2").header("X-User-Role", "ADMIN", "STUDENT")
                .header("X-Username", "someone").header("x-student-id", "student-2");
    }

    private static MockServerWebExchange exchange(String route, MockServerHttpRequest.BaseBuilder<?> request) {
        var exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(route).uri("http://localhost").predicate(ignored -> true).build());
        return exchange;
    }

    private static GatewayFilterChain mustNotForward() {
        return ignored -> fail("Rejected request reached the downstream chain");
    }

    private static JwtBuilder claims(String role) {
        return Jwts.builder()
                .subject("ADMIN".equals(role) ? "admin" : "student-1").claim("username", "student1").claim("role", role)
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
    }

    private static String bearer(JwtBuilder builder) { return "Bearer " + builder.signWith(KEY, Jwts.SIG.HS512).compact(); }
}
