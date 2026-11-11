package com.example.integrationhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;

/**
 * 401/403을 다른 API 오류와 같은 ProblemDetail(JSON) 형식으로 응답한다.
 * WWW-Authenticate 헤더(RFC 6750: error="invalid_token" 등)는 Spring Security 기본 구현에 맡기고 본문만 추가한다.
 * 본문에는 토큰 검증 실패의 내부 사유(만료 시각, 서명 오류 등)를 넣지 않는다.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    public SecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401: 토큰이 없거나, 형식·서명·만료·issuer·audience 검증에 실패한 경우 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, jakarta.servlet.ServletException {
        bearerEntryPoint.commence(request, response, e);
        String detail = e instanceof InvalidBearerTokenException
                ? "Invalid or expired access token"
                : "Authentication required";
        log.info("401 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        write(request, response, HttpStatus.UNAUTHORIZED, detail);
    }

    /** 403: 토큰은 유효하지만 이 API에 필요한 역할이 없는 경우 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException, jakarta.servlet.ServletException {
        bearerAccessDeniedHandler.handle(request, response, e);
        log.info("403 {} {}: user={}", request.getMethod(), request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : null);
        write(request, response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
