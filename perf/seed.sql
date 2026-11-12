-- 성능 측정 전 데이터 초기화. 매 측정 직전에 실행해 모든 측정이 같은 데이터에서 시작하도록 한다 (perf/run.sh).
-- 주문 100,000건(상품 2개씩), 연계 이력 50,000건(주문 1~50,000 SUCCESS). 주문 50,001~100,000은 연계 측정 대상.
truncate order_items, orders, integration_history, idempotency_keys restart identity;

-- 측정 중 재고 부족(409)이 섞이지 않도록 충분히 채운다.
update inventory set quantity = 100000000;

-- 상태 5종을 고르게(각 20%), 고객 1,000명, 생성 시각은 1분 간격
insert into orders (customer_id, status, created_at, version)
select 'customer-' || (g % 1000),
       (array ['CREATED', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'])[1 + g % 5],
       timestamp '2026-01-01' + g * interval '1 minute',
       0
from generate_series(1, 100000) g;

insert into order_items (order_id, product_name, unit_price, quantity)
select o.id, p.name, p.price, 1 + o.id % 3
from orders o
cross join (values ('keyboard', 50000), ('mouse', 20000)) p(name, price)
order by o.id, p.name;

insert into integration_history (order_id, status, erp_order_no, requested_at, completed_at,
                                 requested_by, trigger_type, attempt_count)
select g, 'SUCCESS', 'ERP-' || g,
       timestamp '2026-01-01' + g * interval '1 minute',
       timestamp '2026-01-01' + g * interval '1 minute' + interval '50 millisecond',
       'admin1', 'SYNC', 2
from generate_series(1, 50000) g;

-- 통계를 갱신해 실행 계획이 매번 같은 조건에서 만들어지도록 한다.
vacuum analyze orders, order_items, integration_history, idempotency_keys, inventory;
