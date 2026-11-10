package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [비교용] Stream/Lambda 이전의 명령형 작성 방식.
 * OrderService의 같은 기능과 결과가 동일한지 테스트에서 비교한다. 실무 코드에서는 사용하지 않는다.
 */
public final class LegacyOrderStatistics {

    private LegacyOrderStatistics() {
    }

    public static List<Order> findByCustomer(List<Order> orders, String customerId) {
        List<Order> result = new ArrayList<Order>();
        for (Order order : orders) {
            if (customerId.equals(order.getCustomerId())) {
                result.add(order);
            }
        }
        return result;
    }

    public static Map<String, BigDecimal> totalAmountByCustomer(List<Order> orders) {
        Map<String, BigDecimal> result = new HashMap<String, BigDecimal>();
        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            BigDecimal sum = result.get(order.getCustomerId());
            if (sum == null) {
                sum = BigDecimal.ZERO;
            }
            result.put(order.getCustomerId(), sum.add(order.totalAmount()));
        }
        return result;
    }

    public static Map<OrderStatus, List<Order>> groupByStatus(List<Order> orders) {
        Map<OrderStatus, List<Order>> result = new HashMap<OrderStatus, List<Order>>();
        for (Order order : orders) {
            List<Order> group = result.get(order.getStatus());
            if (group == null) {
                group = new ArrayList<Order>();
                result.put(order.getStatus(), group);
            }
            group.add(order);
        }
        return result;
    }
}
