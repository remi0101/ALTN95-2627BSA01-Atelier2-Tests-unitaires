package pos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Test clock starting at a fixed instant and advancing by a fixed step each time it is read.
 * Guarantees that a read loop waiting for a timeout always terminates quickly.
 */
final class SteppingClock extends Clock {

    private final Instant start;
    private final Duration step;
    private final ZoneId zone;
    private long ticks;

    SteppingClock(Instant start, Duration step, ZoneId zone) {
        this.start = start;
        this.step = step;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return start.plus(step.multipliedBy(ticks++));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new SteppingClock(start, step, zone);
    }
}
