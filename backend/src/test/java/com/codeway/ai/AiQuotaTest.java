package com.codeway.ai;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class AiQuotaTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-11T23:59:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @Test void perUserCapThenNextDayResets() {
        var clock = new MutableClock();
        var q = new AiQuota(3, 100, clock);
        for (int i = 0; i < 3; i++) assertTrue(q.tryAcquire(1L));
        assertFalse(q.tryAcquire(1L), "4th call for user 1 is refused");
        assertTrue(q.tryAcquire(2L), "other users are unaffected");
        clock.now = Instant.parse("2026-11-12T00:00:01Z");
        assertTrue(q.tryAcquire(1L), "new UTC day resets");
    }

    @Test void globalCapCoversEveryoneIncludingSystem() {
        var q = new AiQuota(10, 4, new MutableClock());
        assertTrue(q.tryAcquire(1L)); assertTrue(q.tryAcquire(2L)); assertTrue(q.tryAcquire(null)); assertTrue(q.tryAcquire(3L));
        assertFalse(q.tryAcquire(4L));
        assertFalse(q.tryAcquire(null));
    }

    @Test void zeroMeansNoCap() {
        var q = new AiQuota(0, 0, new MutableClock());
        for (int i = 0; i < 1000; i++) assertTrue(q.tryAcquire(1L));
    }
}
