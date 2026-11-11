package com.example.integrationhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;

/**
 * core-api는 OAuth2 Resource Server다. 로그인 화면이나 비밀번호를 다루지 않고, Keycloak이 발급한 Access Token(JWT)만 검증한다.
 * 서명(JWKS), 만료, issuer, audience 검증은 application.yml의 spring.security.oauth2.resourceserver.jwt.* 설정으로 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    static final String USER = "USER";
    static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
        http
                // Bearer 토큰만 받고 쿠키·세션을 쓰지 않으므로 CSRF 공격 대상이 아니다.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 허용 목록 방식: 여기 없는 요청은 모두 거부한다. 새 API를 추가하면 권한도 함께 정해야 한다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers(GET, "/api/orders", "/api/orders/*").hasRole(USER)
                        .requestMatchers(POST, "/api/orders").hasRole(USER)
                        .requestMatchers(PATCH, "/api/orders/*/status").hasRole(ADMIN)
                        .requestMatchers(DELETE, "/api/orders/*").hasRole(ADMIN)
                        .requestMatchers(POST, "/api/integrations/orders/*/sync").hasRole(ADMIN)
                        .requestMatchers(GET, "/api/integrations/history", "/api/integrations/history/*").hasRole(ADMIN)
                        .requestMatchers(POST, "/api/integrations/history/*/retry").hasRole(ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    @Bean
    SecurityProblemHandler securityProblemHandler(ObjectMapper objectMapper) {
        return new SecurityProblemHandler(objectMapper);
    }

    /** Keycloak Realm Role(realm_access.roles)을 Spring Security 권한(ROLE_USER, ROLE_ADMIN)으로 바꾼다. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realmRoles());
        // 로그에 sub(UUID) 대신 사용자 이름이 보이도록 한다.
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    static Converter<Jwt, Collection<GrantedAuthority>> realmRoles() {
        return jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        };
    }
}
