package com.example.integrationhub.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 테스트용 토큰 발급기. Keycloak처럼 RSA 키로 서명한 JWT를 만들고, 공개키를 JWKS 엔드포인트로 제공한다.
 * core-api는 운영과 같은 설정(JWKS 서명 검증, 만료, issuer, audience)으로 이 토큰을 검증한다.
 * Claim 구조는 Keycloak Access Token과 같다 (realm_access.roles, preferred_username, aud).
 */
public final class TestJwtIssuer {

    /** src/test/resources/application.properties의 OAUTH2_ISSUER_URI와 같아야 한다. */
    public static final String ISSUER = "http://test-issuer/realms/integration-hub";
    public static final String AUDIENCE = "integration-hub-api";

    private static final RSAKey KEY = generateKey("test-key");
    private static final RSAKey UNTRUSTED_KEY = generateKey("untrusted-key");
    private static final HttpServer JWKS_SERVER = startJwksServer();

    private TestJwtIssuer() {
    }

    public static String jwkSetUri() {
        return "http://localhost:" + JWKS_SERVER.getAddress().getPort() + "/certs";
    }

    /** 유효한 Access Token */
    public static String token(String username, String... roles) {
        return sign(claims(username, roles).build());
    }

    /** 기본 Claim. 테스트에서 일부를 바꿔 만료·issuer·audience 오류 토큰을 만든다. */
    public static JWTClaimsSet.Builder claims(String username, String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(List.of(AUDIENCE, "account"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)));
    }

    public static String sign(JWTClaimsSet claims) {
        return sign(KEY, claims);
    }

    /** JWKS에 없는 키로 서명한 토큰 (위조 토큰) */
    public static String signWithUntrustedKey(JWTClaimsSet claims) {
        return sign(UNTRUSTED_KEY, claims);
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
