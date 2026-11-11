package com.example.integrationhub.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 제한된 Retry + Exponential Backoff(+ Jitter).
 * 재시도 가능한 오류(retryable)일 때만, 최대 횟수와 전체 시간 상한 안에서 다시 호출한다.
 * 실패해도 예외를 던지지 않고 마지막 오류와 시도 횟수를 Outcome으로 돌려준다 (호출자가 FAILED 이력으로 기록).
 */
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    public record Outcome<T>(T value, RestClientException error, int attempts) {
        public boolean succeeded() {
            return error == null;
        }
    }

    private final IntegrationProperties.Retry policy;

    public RetryExecutor(IntegrationProperties.Retry policy) {
        this.policy = policy;
    }

    public <T> Outcome<T> execute(String operation, Supplier<T> call, Predicate<RestClientException> retryable) {
        long deadline = System.nanoTime() + policy.maxElapsed().toNanos();
        Duration backoff = policy.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return new Outcome<>(call.get(), null, attempt);
            } catch (RestClientException e) {
                Duration delay = withJitter(backoff);
                if (attempt >= policy.maxAttempts() || !retryable.test(e)
                        || System.nanoTime() + delay.toNanos() > deadline) {
                    return new Outcome<>(null, e, attempt);
                }
                log.warn("Retry {} in {}ms (attempt {}/{} failed): {}",
                        operation, delay.toMillis(), attempt, policy.maxAttempts(), e.getMessage());
                if (!sleep(delay)) {
                    return new Outcome<>(null, e, attempt);
                }
                backoff = next(backoff);
            }
        }
    }

    private Duration next(Duration backoff) {
        Duration increased = Duration.ofMillis((long) (backoff.toMillis() * policy.multiplier()));
        return increased.compareTo(policy.maxBackoff()) > 0 ? policy.maxBackoff() : increased;
    }

    /** 50~100% 범위의 무작위 대기. 여러 요청이 같은 순간에 일제히 재시도해 상대 시스템을 다시 쓰러뜨리는 것을 막는다. */
    private static Duration withJitter(Duration backoff) {
        long millis = backoff.toMillis();
        return Duration.ofMillis(millis / 2 + ThreadLocalRandom.current().nextLong(millis / 2 + 1));
    }

    private static boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
