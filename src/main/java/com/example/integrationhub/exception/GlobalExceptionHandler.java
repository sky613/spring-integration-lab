package com.example.integrationhub.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * 모든 Controller의 예외를 RFC 9457 ProblemDetail 형식으로 변환한다.
 * ResponseEntityExceptionHandler를 상속해 JSON 파싱 오류, 타입 불일치 등 Spring MVC 표준 예외도 같은 형식(400)으로 응답한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({OrderNotFoundException.class, IntegrationHistoryNotFoundException.class,
            ProductNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({InvalidOrderStatusException.class, InsufficientStockException.class,
            IntegrationNotRetryableException.class, IntegrationInProgressException.class})
    public ProblemDetail handleConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 다른 요청이 같은 주문을 먼저 수정한 경우 (@Version 충돌). 내부 메시지는 노출하지 않는다. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Order was modified concurrently. Please retry.");
    }

    /** 같은 Idempotency-Key로 다른 내용의 요청을 보낸 경우 */
    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ProblemDetail handleIdempotencyKeyMismatch(IdempotencyKeyMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Redis·DB 연결 실패 등 일시적 인프라 장애. 내부 메시지는 노출하지 않고 503으로 재시도를 유도한다. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ProblemDetail handleResourceFailure(DataAccessResourceFailureException e) {
        log.warn("Infrastructure unavailable: {}", e.toString());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable. Please retry.");
    }

    @ExceptionHandler(UnknownProductException.class)
    public ProblemDetail handleUnknownProduct(UnknownProductException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 존재하지 않는 필드로 정렬을 요청한 경우 (예: sort=unknown). */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleInvalidSort(PropertyReferenceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid sort property: " + e.getPropertyName());
    }

    /** @Valid 검증 실패 시 어떤 필드가 왜 실패했는지 errors 속성에 담는다. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }
}
