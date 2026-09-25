package com.emplmgt.util;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Centralised application clock. All "today" calculations use the configured
 * application timezone so that results are consistent regardless of server timezone.
 */
@Component
@Getter
public class AppClock {

    private final Clock clock;

    public AppClock(@Value("${application.timezone:UTC}") String timezone) {
        this.clock = Clock.system(ZoneId.of(timezone));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }
}