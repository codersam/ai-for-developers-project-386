package com.hexlet.calendar.web;

import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.generated.model.CreateScheduledEvent;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import com.hexlet.calendar.service.ScheduledEventService;
import com.hexlet.calendar.web.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Note: extends AbstractIntegrationTest to share the Testcontainers Postgres + Spring context
 * cache; deliberately does NOT add @Transactional so the two threads run in two real
 * transactions and the EXCLUDE constraint actually has something to serialize.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    private static final String EVENT_TYPE_A = "et_concurrent_a_aaaaaa";
    private static final String EVENT_TYPE_B = "et_concurrent_b_bbbbbb";

    @Autowired
    private ScheduledEventService scheduledEventService;

    @AfterEach
    void cleanup() {
        scheduledEventRepo.deleteAll();
        eventTypeRepo.deleteAll();
    }

    private void persistEventType(String id) {
        EventTypeEntity et = new EventTypeEntity();
        et.setId(id);
        et.setName("Concurrent");
        et.setDescription("d");
        et.setDurationMinutes(30);
        eventTypeRepo.save(et);
    }

    private CreateScheduledEvent payload(String eventTypeId) {
        CreateScheduledEvent p = new CreateScheduledEvent();
        p.setEventTypeId(eventTypeId);
        p.setDate(LocalDate.parse("2026-05-04"));
        p.setTime("10:00:00");
        p.setSubject("s");
        p.setNotes("n");
        p.setGuestName("g");
        p.setGuestEmail("g@e.com");
        p.setGuestTimezone("Europe/Berlin");
        return p;
    }

    @Test
    void twoConcurrentBookings_oneSucceedsOneConflicts() throws Exception {
        persistEventType(EVENT_TYPE_A);
        persistEventType(EVENT_TYPE_B);

        CreateScheduledEvent payloadA = payload(EVENT_TYPE_A);
        CreateScheduledEvent payloadB = payload(EVENT_TYPE_B);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);

            Callable<Result> taskA = () -> {
                barrier.await();
                try {
                    return Result.ok(scheduledEventService.create(payloadA));
                } catch (Throwable t) {
                    return Result.fail(t);
                }
            };
            Callable<Result> taskB = () -> {
                barrier.await();
                try {
                    return Result.ok(scheduledEventService.create(payloadB));
                } catch (Throwable t) {
                    return Result.fail(t);
                }
            };

            Future<Result> fa = pool.submit(taskA);
            Future<Result> fb = pool.submit(taskB);

            List<Result> results = List.of(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS));

            long ok = results.stream().filter(Result::isOk).count();
            long fail = results.stream().filter(r -> !r.isOk()).count();

            assertThat(ok).as("exactly one booking succeeds").isEqualTo(1);
            assertThat(fail).as("exactly one booking fails").isEqualTo(1);

            Throwable failure = results.stream()
                    .filter(r -> !r.isOk())
                    .map(Result::error)
                    .findFirst()
                    .orElseThrow();
            assertThat(failure)
                    .isInstanceOfAny(ConflictException.class, DataIntegrityViolationException.class);

            assertThat(scheduledEventRepo.count())
                    .as("exactly one row landed in the DB")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    record Result(ScheduledEvent dto, Throwable error) {
        static Result ok(ScheduledEvent d) { return new Result(d, null); }
        static Result fail(Throwable t)     { return new Result(null, t); }
        boolean isOk() { return error == null; }
    }
}
