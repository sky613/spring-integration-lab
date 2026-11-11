package com.example.integrationhub.client;

/** erpOrderNo: ERP가 발급한 주문 번호. result: 접수 결과 (예: RECEIVED). */
public record ErpOrderResponse(String erpOrderNo, String result) {
}
