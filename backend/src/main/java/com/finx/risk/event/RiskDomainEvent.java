package com.finx.risk.event;

import java.time.Instant;
import java.util.UUID;

public record RiskDomainEvent(UUID auditId, String action, String entityType, UUID entityId, Instant occurredAt) {}
