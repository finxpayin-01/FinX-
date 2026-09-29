package com.finx.common.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

@Component
public class FinxClock {

    private Clock clock = Clock.systemUTC();

    public Instant now() {
        return clock.instant();
    }

    public Clock getClock() {
        return clock;
    }

    public synchronized void setFixed(Instant fixedInstant) {
        this.clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
    }

    public synchronized void advance(Duration duration) {
        this.clock = Clock.fixed(this.clock.instant().plus(duration), ZoneOffset.UTC);
    }

    public synchronized void reset() {
        this.clock = Clock.systemUTC();
    }
}
