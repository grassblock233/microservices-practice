package com.zjgsu.lht.gateway;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayRoutesTest {
    @Test
    void composeRoutesBindAndAreProtectedWithoutExposingInternalPaths() throws IOException {
        var key = Jwts.SIG.HS512.key().build();
        String secret = Base64.getEncoder().encodeToString(key.getEncoded());
        String token = Jwts.builder().subject("student-1").claim("studentId", "student-1")
                .claim("username", "student1").claim("role", "STUDENT").issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key, Jwts.SIG.HS512).compact();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .contextFactory(type -> new AnnotationConfigReactiveWebApplicationContext())
                .run(
                        "--spring.application.json=" + composeGatewayConfiguration(),
                        "--JWT_SECRET=" + secret,
                        "--spring.cloud.nacos.discovery.enabled=false",
                        "--spring.cloud.discovery.enabled=false",
                        "--spring.cloud.service-registry.auto-registration.enabled=false")) {
            var routed = context.getBean("cachedCompositeRouteLocator", RouteLocator.class).getRoutes()
                    .collectMap(route -> route.getId(), route -> route.getUri().toString()).block(Duration.ofSeconds(5));
            assertEquals(Map.of("login", "lb://user-service", "users", "lb://user-service",
                    "dishes", "lb://dish-service", "orders", "lb://order-service"), routed);

            var client = WebTestClient.bindToApplicationContext((ApplicationContext) context).build();
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
            client.get().uri("/api/users/student-1").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/dishes?merchantId=10").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/orders?userId=student-1").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/orders/sample-order").exchange().expectStatus().isUnauthorized();
            client.post().uri("/api/orders").exchange().expectStatus().isUnauthorized();
            client.post().uri("/internal/stock/deduct").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isNotFound();
            client.get().uri("/api/auth/login").exchange().expectStatus().isNotFound();
            // There is no discovered user instance in this test; reaching load balancing proves login is public.
            client.post().uri("/api/auth/login").exchange().expectStatus().isEqualTo(503);
            client.get().uri("/api/users/student-1").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isEqualTo(503);
            client.get().uri("/api/users/student-2").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isForbidden();
        }
    }

    private static String composeGatewayConfiguration() throws IOException {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path compose = directory.resolve("compose.yaml");
            if (Files.isRegularFile(compose)) {
                for (var source : new YamlPropertySourceLoader().load("compose", new FileSystemResource(compose))) {
                    Object routes = source.getProperty("services.gateway-service.environment.SPRING_APPLICATION_JSON");
                    if (routes instanceof String configuration) return configuration;
                }
                throw new IllegalStateException("Missing gateway route configuration in " + compose);
            }
        }
        throw new IllegalStateException("Cannot locate the repository compose.yaml");
    }
}
