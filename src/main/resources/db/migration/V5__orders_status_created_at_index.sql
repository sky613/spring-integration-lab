-- 주문 목록(GET /api/orders?status=...&sort=createdAt,desc)용 복합 인덱스 (STEP 9 성능 측정).
-- 기존 idx_orders_status만으로는 해당 상태 전체(10만 건 중 2만 건)를 읽고 정렬한 뒤 20건을 잘랐다 (EXPLAIN 20.3ms).
-- (status, created_at)이면 인덱스 순서대로 필요한 행만 읽는다 (0.13ms). 앞 컬럼이 status라 상태 단독 조건도 이 인덱스로 처리된다.
create index idx_orders_status_created_at on orders (status, created_at);
drop index idx_orders_status;
