package com.finx.risk.event;

import com.finx.risk.dto.RiskDtos.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Server-side contract for structured telemetry not yet collected by the core domain models. */
public record RiskSignalObserved(UUID subjectId,EventType eventType,String sourceKey,
                                 String evidenceReference,Instant occurredAt,Map<Metric,Double> values) {
    public enum Metric {
        DISPUTES_LOST("disputesLost",EventType.DISPUTE_RESOLVED),
        DUPLICATE_INVOICES("duplicateInvoices",EventType.INVOICE_UPLOADED),
        INVOICE_MISMATCH("invoiceMismatch",EventType.INVOICE_UPLOADED),
        DOCUMENT_CHANGES("documentChanges",EventType.INVOICE_UPLOADED),
        DUPLICATE_DOCUMENTS("duplicateDocuments",EventType.INVOICE_UPLOADED),
        NEW_DEVICE_HIGH_VALUE("newDeviceHighValue",EventType.SECURITY_EVENT);
        public final String metric;
        public final EventType eventType;
        Metric(String metric,EventType eventType) { this.metric=metric; this.eventType=eventType; }
    }
    public record SafeEvidence(String reference,Map<Metric,Double> values) {}
}
