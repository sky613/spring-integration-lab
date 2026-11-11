package com.example.integrationhub.controller;

import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.dto.OrderStatusRequest;
import com.example.integrationhub.service.IdempotentOrderService;
import com.example.integrationhub.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern VALID_IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final OrderService orderService;
    private final IdempotentOrderService idempotentOrderService;

    public OrderController(OrderService orderService, IdempotentOrderService idempotentOrderService) {
        this.orderService = orderService;
        this.idempotentOrderService = idempotentOrderService;
    }

    /**
     * 201 Created + Location 헤더로 생성된 리소스 위치를 알려준다.
     * Idempotency-Key(선택)를 보내면 같은 키의 재요청은 새 주문을 만들지 않고 처음 결과를 돌려준다(Idempotent-Replayed: true).
     * 같은 키로 다른 내용을 보내면 422. 키는 사용자별로 구분한다.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            Authentication authentication) {
        if (idempotencyKey == null) {
            return created(orderService.create(request), false);
        }
        // 파라미터에 @Pattern을 붙이면 메서드 검증이 켜져 @Valid 본문 오류 응답 형식(errors 필드)까지 바뀌므로 직접 검증한다.
        if (!VALID_IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-100 characters of [A-Za-z0-9_.:-]");
        }
        IdempotentOrderService.Result result = idempotentOrderService.create(request, authentication.getName(), idempotencyKey);
        return created(result.order(), result.replayed());
    }

    private static ResponseEntity<OrderResponse> created(OrderResponse response, boolean replayed) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        ResponseEntity.BodyBuilder builder = ResponseEntity.created(location);
        if (replayed) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(response);
    }

    /**
     * 기존 계약(JSON 배열 응답)을 유지하고, 페이징 정보는 X-Total-Count 헤더로 전달한다.
     * 예: GET /api/orders?status=PAID&page=0&size=10&sort=createdAt,desc
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> findAll(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        Page<OrderResponse> page = orderService.findAll(status, pageable);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.getTotalElements()))
                .body(page.getContent());
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable Long id) {
        return orderService.findById(id);
    }

    /** 리소스 일부(상태)만 바꾸므로 PUT이 아닌 PATCH를 사용한다. */
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusRequest request) {
        return orderService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        orderService.delete(id);
    }
}
