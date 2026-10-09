package com.finx.release;

import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePersistenceEntityMappingTest {

    @Test
    @DisplayName("ReleaseInstruction entity initializes with expected immutable snapshots")
    void releaseInstructionSnapshotInitialization() {
        UUID orgId = UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID releaseDecisionId = UUID.randomUUID();
        String releaseDecisionFp = "a".repeat(64);
        UUID policyVersionId = UUID.randomUUID();
        String policyHash = "b".repeat(64);
        UUID beneficiaryBankAccId = UUID.randomUUID();
        String provider = "MOCK";
        String providerAccountId = "ACC-12345";
        BigDecimal amount = new BigDecimal("50000.0000");
        String currency = "INR";
        String idempotencyKey = "REL-IDEM-001";
        String requestHash = "c".repeat(64);
        UUID createdBy = UUID.randomUUID();

        ReleaseInstruction instruction = new ReleaseInstruction(
                null, orgId, dealId, milestoneId, releaseDecisionId, releaseDecisionFp,
                policyVersionId, policyHash, beneficiaryBankAccId, provider, providerAccountId,
                amount, currency, idempotencyKey, requestHash, createdBy
        );

        assertThat(instruction.getId()).isNotNull();
        assertThat(instruction.getOrganizationId()).isEqualTo(orgId);
        assertThat(instruction.getDealId()).isEqualTo(dealId);
        assertThat(instruction.getMilestoneId()).isEqualTo(milestoneId);
        assertThat(instruction.getReleaseDecisionId()).isEqualTo(releaseDecisionId);
        assertThat(instruction.getReleaseDecisionFingerprint()).isEqualTo(releaseDecisionFp);
        assertThat(instruction.getPolicyVersionId()).isEqualTo(policyVersionId);
        assertThat(instruction.getPolicyContentHash()).isEqualTo(policyHash);
        assertThat(instruction.getBeneficiaryBankAccountId()).isEqualTo(beneficiaryBankAccId);
        assertThat(instruction.getProvider()).isEqualTo(provider);
        assertThat(instruction.getProviderAccountId()).isEqualTo(providerAccountId);
        assertThat(instruction.getAmount()).isEqualTo(amount);
        assertThat(instruction.getCurrency()).isEqualTo("INR");
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(instruction.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(instruction.getRequestHash()).isEqualTo(requestHash);
        assertThat(instruction.getCreatedBy()).isEqualTo(createdBy);
        assertThat(instruction.getAuthorizedBy()).isNull();
        assertThat(instruction.getAuthorizedAt()).isNull();
        assertThat(instruction.getSettledAt()).isNull();
        assertThat(instruction.getVersion()).isNull();
        assertThat(instruction.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("ReleaseInstruction rejects non-positive amount")
    void releaseInstructionRejectsNonPositiveAmount() {
        UUID orgId = UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID beneficiaryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> new ReleaseInstruction(
                null, orgId, dealId, milestoneId, decisionId, "a".repeat(64),
                policyId, "b".repeat(64), beneficiaryId, "MOCK", "ACC-1",
                BigDecimal.ZERO, "INR", "KEY-1", "c".repeat(64), userId
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Release amount must be strictly positive");

        assertThatThrownBy(() -> new ReleaseInstruction(
                null, orgId, dealId, milestoneId, decisionId, "a".repeat(64),
                policyId, "b".repeat(64), beneficiaryId, "MOCK", "ACC-1",
                new BigDecimal("-100.00"), "INR", "KEY-1", "c".repeat(64), userId
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReleaseReservation initializes as ACTIVE with positive amount")
    void releaseReservationInitialization() {
        UUID instructionId = UUID.randomUUID();
        UUID escrowAccId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("25000.0000");

        ReleaseReservation reservation = new ReleaseReservation(
                null, instructionId, escrowAccId, milestoneId, amount, "INR"
        );

        assertThat(reservation.getId()).isNotNull();
        assertThat(reservation.getReleaseInstructionId()).isEqualTo(instructionId);
        assertThat(reservation.getEscrowAccountId()).isEqualTo(escrowAccId);
        assertThat(reservation.getMilestoneId()).isEqualTo(milestoneId);
        assertThat(reservation.getAmount()).isEqualTo(amount);
        assertThat(reservation.getCurrency()).isEqualTo("INR");
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(reservation.getConsumedAt()).isNull();
        assertThat(reservation.getReleasedAt()).isNull();
        assertThat(reservation.getVersion()).isNull();
    }

    @Test
    @DisplayName("ReleaseReservation lifecycle transitions mutate status and timestamps")
    void releaseReservationLifecycleTransitions() {
        ReleaseReservation reservation = new ReleaseReservation(
                null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1000.0000"), "INR"
        );

        Instant now = Instant.now();
        reservation.markConsumed(now);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(reservation.getConsumedAt()).isEqualTo(now);

        // Cannot consume again
        assertThatThrownBy(() -> reservation.markConsumed(Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // Cannot release once consumed
        assertThatThrownBy(() -> reservation.markReleased(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
