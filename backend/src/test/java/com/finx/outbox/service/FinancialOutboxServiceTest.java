package com.finx.outbox.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOperationKey;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox_svc_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "rate-limit.enabled=false"
})
public class FinancialOutboxServiceTest {

    @Autowired
    private FinancialOutboxService financialOutboxService;

    @MockBean
    private FinancialOutboxRepository outboxRepository;

    @MockBean
    private com.finx.ledger.service.LedgerService ledgerService;

    @Autowired
    private FinxClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
    }

    private ReleaseInstruction createInstruction(UUID id, ReleaseInstructionStatus status) {
        ReleaseInstruction instruction = new ReleaseInstruction(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dummy-fp",
                UUID.randomUUID(),
                "dummy-hash",
                UUID.randomUUID(),
                "MOCK",
                "MOCK-ACC-1",
                BigDecimal.valueOf(50000),
                "INR",
                "IDEM-" + UUID.randomUUID(),
                "REQ-HASH",
                UUID.randomUUID()
        );

        if (status == ReleaseInstructionStatus.AUTHORIZED) {
            instruction.markAuthorized(UUID.randomUUID(), Instant.now());
        } else if (status == ReleaseInstructionStatus.RESERVED) {
            instruction.markAuthorized(UUID.randomUUID(), Instant.now());
            instruction.markReserved();
        } else if (status == ReleaseInstructionStatus.QUEUED) {
            instruction.markAuthorized(UUID.randomUUID(), Instant.now());
            instruction.markReserved();
            instruction.markQueued();
        } else if (status == ReleaseInstructionStatus.SETTLED) {
            instruction.markAuthorized(UUID.randomUUID(), Instant.now());
            instruction.markReserved();
            instruction.markQueued();
            instruction.markSettled(Instant.now());
        } else if (status == ReleaseInstructionStatus.CANCELLED) {
            instruction.markCancelled();
        }
        return instruction;
    }

    @Test
    @DisplayName("1. RESERVED instruction enqueues PENDING event with deterministic operation identity")
    void testReservedInstructionEnqueuesPendingEvent() {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.RESERVED);

        when(outboxRepository.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.empty());
        when(outboxRepository.findByEventTypeAndOperationKey(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED, instructionId.toString()))
                .thenReturn(Optional.empty());

        when(outboxRepository.save(any(FinancialOutboxMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FinancialOutboxMessage result = txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        );

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);
        assertThat(result.getEventType()).isEqualTo(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED);
        assertThat(result.getReleaseInstructionId()).isEqualTo(instructionId);
        assertThat(result.getOperationKey()).isEqualTo(instructionId.toString());
        assertThat(result.getPayloadVersion()).isEqualTo(1);
        assertThat(result.getAttemptCount()).isEqualTo(0);
        assertThat(result.getLockedAt()).isNull();
        assertThat(result.getLockedBy()).isNull();
        assertThat(result.getProcessedAt()).isNull();
        assertThat(result.getDeadLetteredAt()).isNull();

        verify(outboxRepository, times(1)).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("2. Operation identity is deterministic and stable across calls")
    void testOperationIdentityDeterministic() {
        UUID instructionId = UUID.randomUUID();
        String opKey1 = FinancialOperationKey.releaseDispatch(instructionId);
        String opKey2 = FinancialOperationKey.releaseDispatch(instructionId);
        assertThat(opKey1).isEqualTo(opKey2);
        assertThat(opKey1).isEqualTo(instructionId.toString());
    }

    @Test
    @DisplayName("3 & 4. Same instruction replay returns existing event without creating a second row")
    void testSameInstructionReplayReturnsExisting() {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.RESERVED);

        FinancialOutboxMessage existing = FinancialOutboxMessage.createReleaseDispatch(instruction, Instant.now());

        when(outboxRepository.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.of(existing));

        FinancialOutboxMessage result = txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        );

        assertThat(result).isSameAs(existing);
        verify(outboxRepository, never()).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("5. Conflicting event identity fails closed")
    void testConflictingEventIdentityFailsClosed() {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.RESERVED);

        // Preexisting outbox row for a DIFFERENT instruction that has the same operation key
        UUID otherInstructionId = UUID.randomUUID();
        FinancialOutboxMessage conflicting = new FinancialOutboxMessage(
                UUID.randomUUID(),
                otherInstructionId,
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instructionId.toString(),
                1,
                FinancialOutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );

        when(outboxRepository.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.empty());
        when(outboxRepository.findByEventTypeAndOperationKey(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED, instructionId.toString()))
                .thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTBOX_IDENTITY_CONFLICT");

        verify(outboxRepository, never()).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("6. CREATED instruction rejected")
    void testCreatedInstructionRejected() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.CREATED);

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires RESERVED instruction status");
    }

    @Test
    @DisplayName("7. AUTHORIZED instruction rejected")
    void testAuthorizedInstructionRejected() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.AUTHORIZED);

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires RESERVED instruction status");
    }

    @Test
    @DisplayName("8. QUEUED direct enqueue rejected")
    void testQueuedInstructionRejected() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.QUEUED);

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires RESERVED instruction status");
    }

    @Test
    @DisplayName("9. SETTLED instruction rejected")
    void testSettledInstructionRejected() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.SETTLED);

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires RESERVED instruction status");
    }

    @Test
    @DisplayName("10. CANCELLED instruction rejected")
    void testCancelledInstructionRejected() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.CANCELLED);

        assertThatThrownBy(() -> txTemplate.execute(status ->
                financialOutboxService.enqueueReleaseDispatch(instruction)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires RESERVED instruction status");
    }

    @Test
    @DisplayName("11. Propagation.MANDATORY: Calling outside transaction throws IllegalTransactionStateException")
    void testCallingOutsideTransactionThrows() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.RESERVED);

        assertThatThrownBy(() -> financialOutboxService.enqueueReleaseDispatch(instruction))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("12. Method reflection proves @Transactional(propagation = MANDATORY)")
    void testTransactionalAnnotationConfiguration() throws Exception {
        Method method = FinancialOutboxService.class.getMethod("enqueueReleaseDispatch", ReleaseInstruction.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
