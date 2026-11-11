package com.example.integrationhub.controller;

import com.example.integrationhub.domain.IntegrationStatus;
import com.example.integrationhub.dto.IntegrationHistoryResponse;
import com.example.integrationhub.service.IntegrationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final IntegrationService integrationService;

    public IntegrationController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    /**
     * 연계를 실행하고 이력 1건을 만든다. 외부 시스템이 실패해도 이력은 생성되므로 201로 응답하고,
     * 성공/실패는 본문의 status(SUCCESS/FAILED)로 구분한다.
     */
    @PostMapping("/orders/{id}/sync")
    public ResponseEntity<IntegrationHistoryResponse> syncOrder(@PathVariable Long id) {
        IntegrationHistoryResponse response = integrationService.syncOrder(id);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/integrations/history/{historyId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /** 예: GET /api/integrations/history?orderId=1&status=FAILED&page=0&size=10 (기본: 최신순) */
    @GetMapping("/history")
    public ResponseEntity<List<IntegrationHistoryResponse>> findHistories(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) IntegrationStatus status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<IntegrationHistoryResponse> page = integrationService.findHistories(orderId, status, pageable);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.getTotalElements()))
                .body(page.getContent());
    }

    @GetMapping("/history/{id}")
    public IntegrationHistoryResponse findHistory(@PathVariable Long id) {
        return integrationService.findHistory(id);
    }
}
