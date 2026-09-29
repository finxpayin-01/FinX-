package com.finx.risk.config;

import com.finx.risk.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.UUID;

@Configuration
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name="finx.risk.worker-enabled",havingValue="true",matchIfMissing=true)
public class RiskWorker {
    private static final Logger log=LoggerFactory.getLogger(RiskWorker.class);
    private final RiskEventService events;
    private final RiskEventProcessor processor;
    public RiskWorker(RiskEventService events,RiskEventProcessor processor) { this.events=events; this.processor=processor; }
    @Scheduled(fixedDelayString="${finx.risk.poll-ms:10000}",initialDelayString="${finx.risk.initial-delay-ms:30000}")
    public void tick() {
        try {
            events.discover(50);
            for(UUID id:processor.pending()) {
                try { processor.process(id); }
                catch(Exception e) { log.error("Risk event {} failed",id,e); processor.failed(id); }
            }
            processor.refresh();
        } catch(Exception e) { log.error("Risk worker batch failed; next poll will retry",e); }
    }
}
