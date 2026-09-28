package com.finx.trust.event;

import com.finx.trust.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Trusted server-to-server contract. There is deliberately no buyer/vendor ingestion endpoint. */
public record TrustEventInput(UUID organizationId, UUID userId, UUID dealId, UUID milestoneId,
        UUID counterpartyId, TrustRole role, TrustEventType eventType, BigDecimal eventValue,
        String currency, int severity, Instant occurredAt, Map<String,Object> metadata,
        String source, String sourceKey) {}
