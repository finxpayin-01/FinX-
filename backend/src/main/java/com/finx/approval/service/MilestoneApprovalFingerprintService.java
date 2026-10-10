package com.finx.approval.service;

import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure deterministic cryptographic fingerprint service for durable milestone approval bindings.
 * Zero database writes, zero randomness, zero internal clock reads.
 */
@Service
public class MilestoneApprovalFingerprintService {

    /**
     * Computes the SHA-256 fingerprint for material milestone fields.
     */
    public String computeMilestoneFingerprint(Milestone milestone) {
        Objects.requireNonNull(milestone, "milestone must not be null");
        String normalizedAmount = milestone.getAmount() != null
                ? milestone.getAmount().stripTrailingZeros().toPlainString()
                : "0";
        String normalizedCurrency = milestone.getCurrency() != null
                ? milestone.getCurrency().trim().toUpperCase()
                : "INR";
        String statusStr = milestone.getStatus() != null ? milestone.getStatus().name() : "NULL";
        String dueDateStr = milestone.getDueDate() != null ? milestone.getDueDate().toString() : "null";
        String deadlineStr = milestone.getSubmissionDeadline() != null ? milestone.getSubmissionDeadline().toString() : "null";

        String canonical = String.join("|",
                milestone.getId() != null ? milestone.getId().toString() : "",
                milestone.getDealId() != null ? milestone.getDealId().toString() : "",
                String.valueOf(milestone.getSequence()),
                normalizedAmount,
                normalizedCurrency,
                String.valueOf(milestone.isInvoiceRequired()),
                dueDateStr,
                deadlineStr,
                statusStr
        );
        return sha256Hex(canonical);
    }

    /**
     * Computes the SHA-256 fingerprint for milestone deliverables.
     * Sorts deterministically by deliverable ID to ensure collection order independence.
     */
    public String computeDeliverablesFingerprint(List<Deliverable> deliverables) {
        if (deliverables == null || deliverables.isEmpty()) {
            return sha256Hex("EMPTY_DELIVERABLES");
        }

        List<String> entries = deliverables.stream()
                .sorted(Comparator.comparing(Deliverable::getId))
                .map(d -> String.join("|",
                        d.getId() != null ? d.getId().toString() : "",
                        d.getMilestoneId() != null ? d.getMilestoneId().toString() : "",
                        d.getSubmittedBy() != null ? d.getSubmittedBy().toString() : "",
                        d.getSubmittedAt() != null ? d.getSubmittedAt().toString() : "null",
                        d.getFileUrl() != null ? d.getFileUrl().trim() : "",
                        d.getStatus() != null ? d.getStatus().name() : "NULL",
                        d.getReviewedBy() != null ? d.getReviewedBy().toString() : "null",
                        d.getReviewedAt() != null ? d.getReviewedAt().toString() : "null"
                ))
                .toList();

        String canonical = String.join("\n", entries);
        return sha256Hex(canonical);
    }

    /**
     * Computes the SHA-256 approval binding fingerprint over all contextual approval evidence.
     */
    public String computeApprovalFingerprint(
            UUID organizationId,
            UUID dealId,
            UUID milestoneId,
            UUID approvedBy,
            MilestoneApprovalAuthority approvalAuthority,
            UUID projectManagerId,
            String milestoneFingerprint,
            String deliverablesFingerprint,
            Instant approvedAt
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        Objects.requireNonNull(approvedBy, "approvedBy must not be null");
        Objects.requireNonNull(approvalAuthority, "approvalAuthority must not be null");
        Objects.requireNonNull(milestoneFingerprint, "milestoneFingerprint must not be null");
        Objects.requireNonNull(deliverablesFingerprint, "deliverablesFingerprint must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");

        String pmStr = projectManagerId != null ? projectManagerId.toString() : "null";

        String canonical = String.join("|",
                organizationId.toString(),
                dealId.toString(),
                milestoneId.toString(),
                approvedBy.toString(),
                approvalAuthority.name(),
                pmStr,
                milestoneFingerprint,
                deliverablesFingerprint,
                approvedAt.toString()
        );
        return sha256Hex(canonical);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
