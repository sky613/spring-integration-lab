package com.example.integrationhub.domain;

public enum IntegrationErrorType {
    /** Connection Timeout 또는 Read Timeout 초과 */
    TIMEOUT,
    /** 연결 거부, 연결 끊김 등 네트워크 오류 */
    CONNECTION_FAILED,
    /** 외부 시스템 5xx */
    SERVER_ERROR,
    /** 외부 시스템 4xx (요청 형식 오류 등) */
    CLIENT_ERROR,
    /** 응답 본문이 없거나 해석할 수 없음 */
    INVALID_RESPONSE,
    /** 통신은 성공했지만 업무적으로 거절됨 (예: MES 생산 불가) */
    REJECTED
}
