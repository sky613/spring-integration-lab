package com.example.integrationhub.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetryExecutorTest {

    private static final IntegrationProperties.Retry POLICY =
            new IntegrationProperties.Retry(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(50), Duration.ofSeconds(2));

    private final RetryExecutor executor = new RetryExecutor(POLICY);
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void succeedsAfterTransientFailures() {
        RetryExecutor.Outcome<String> outcome = executor.execute("test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw unavailable();
            }
            return "ok";
        }, e -> true);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.value()).isEqualTo("ok");
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void stopsAtMaxAttemptsAndReturnsLastError() {
        RetryExecutor.Outcome<String> outcome = executor.execute("test", () -> {
            calls.incrementAndGet();
            throw unavailable();
        }, e -> true);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.error()).isInstanceOf(HttpServerErrorException.class);
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryNonRetryableError() {
        RetryExecutor.Outcome<String> outcome = executor.execute("test", () -> {
            calls.incrementAndGet();
            throw new RestClientException("bad request");
        }, e -> false);

        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    /** 다음 대기 후 시작 시점이 전체 시간 상한을 넘으면 최대 횟수 전이라도 멈춘다. */
    @Test
    void stopsWhenNextAttemptWouldExceedMaxElapsed() {
        RetryExecutor shortBudget = new RetryExecutor(new IntegrationProperties.Retry(
                10, Duration.ofMillis(200), 2.0, Duration.ofSeconds(1), Duration.ofMillis(300)));

        RetryExecutor.Outcome<String> outcome = shortBudget.execute("test", () -> {
            calls.incrementAndGet();
            throw unavailable();
        }, e -> true);

        assertThat(outcome.attempts()).isLessThan(10);
        assertThat(calls.get()).isBetween(1, 3);
    }

    private static HttpServerErrorException unavailable() {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null);
    }
}
