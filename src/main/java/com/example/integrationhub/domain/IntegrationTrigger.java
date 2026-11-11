package com.example.integrationhub.domain;

/** 연계 실행 방식 (감사 정보) */
public enum IntegrationTrigger {
    /** POST /api/integrations/orders/{id}/sync */
    SYNC,
    /** POST /api/integrations/history/{id}/retry (실패 재처리) */
    RETRY
}
