package com.finx.paymentorchestration;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TEST-ONLY fixture under src/test to establish funded deal state for downstream integration tests
 * without relying on the disabled manual confirmation endpoint.
 * This class is not packaged into production builds.
 */
@TestComponent
public class TestEscrowFundingFixture {

    private final PaymentInstructionRepository instructionRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final EscrowService escrowService;
    private final DealRepository dealRepository;

    public TestEscrowFundingFixture(PaymentInstructionRepository instructionRepository,
                                   PaymentTransactionRepository transactionRepository,
                                   LedgerService ledgerService,
                                   EscrowService escrowService,
                                   DealRepository dealRepository) {
        this.instructionRepository = instructionRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.escrowService = escrowService;
        this.dealRepository = dealRepository;
    }

    @Transactional
    public void simulateFundedDeal(UUID instructionId, String utr, UUID actorId) {
        PaymentInstruction instruction = instructionRepository.findById(instructionId)
                .orElseThrow(() -> new IllegalArgumentException("Instruction not found: " + instructionId));

        instruction.setStatus(InstructionStatus.SUCCESS);
        instruction.setConfirmedAt(Instant.now());
        PaymentInstruction savedInstruction = instructionRepository.save(instruction);

        PaymentTransaction tx = new PaymentTransaction(
                "TXN-" + savedInstruction.getInstructionReference(),
                savedInstruction.getId(),
                savedInstruction.getProvider() != null ? savedInstruction.getProvider() : "BANK",
                savedInstruction.getProviderInstructionId(),
                savedInstruction.getAmount(),
                savedInstruction.getCurrency(),
                "SUCCESS",
                utr,
                "{\"status\": \"SUCCESS\", \"testFixture\": true}"
        );
        transactionRepository.save(tx);

        PostJournalEntryRequest journalReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                savedInstruction.getId(),
                savedInstruction.getDealId(),
                savedInstruction.getMilestoneId(),
                savedInstruction.getCurrency(),
                "Test fixture bank deposit via UTR " + utr + " for deal " + savedInstruction.getDealId(),
                actorId != null ? actorId : savedInstruction.getInitiatedBy(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, savedInstruction.getAmount(), "Escrow pool bank nodal deposit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, savedInstruction.getAmount(), "Deal escrow liability created")
                )
        );
        ledgerService.postJournalEntry(journalReq);

        escrowService.fundEscrowForInstruction(
                savedInstruction.getDealId(),
                savedInstruction.getMilestoneId(),
                savedInstruction.getId(),
                savedInstruction.getAmount(),
                savedInstruction.getCurrency(),
                actorId != null ? actorId : savedInstruction.getInitiatedBy()
        );

        Deal deal = dealRepository.findById(savedInstruction.getDealId()).orElse(null);
        if (deal != null) {
            deal.setStatus(DealStatus.FUNDED);
            dealRepository.save(deal);
        }
    }
}
