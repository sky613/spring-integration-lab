package com.example.gateway.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * TCP 연결의 실제 상대 주소. X-Forwarded-For는 클라이언트가 위조할 수 있으므로 쓰지 않는다.
 * shortcut: Gateway 앞에 Load Balancer를 두면 모든 요청이 LB 주소로 보인다. 그때는 신뢰하는 Proxy 목록과 함께 XForwardedRemoteAddressResolver로 바꾼다.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
