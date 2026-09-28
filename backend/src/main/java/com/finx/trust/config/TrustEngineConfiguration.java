package com.finx.trust.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.finx.trust.engine.TrustScoringEngine;
import java.time.Clock;

@Configuration
@EnableScheduling
public class TrustEngineConfiguration {
    @Bean public Clock trustClock() { return Clock.systemUTC(); }
    @Bean public TrustScoringEngine trustScoringEngine() { return new TrustScoringEngine(); }
}
