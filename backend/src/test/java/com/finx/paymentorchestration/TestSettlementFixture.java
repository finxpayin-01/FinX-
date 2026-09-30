package com.finx.paymentorchestration;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST-ONLY fixture under src/test to establish settled milestone state for downstream integration tests
 * without relying on the disabled live settlement execution path.
 * This class is not packaged into production builds.
 */
@TestComponent
public class TestSettlementFixture {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private final SettlementInstructionRepository settlementRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealRepository dealRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;
    private final LedgerService ledgerService;

    public TestSettlementFixture(SettlementInstructionRepository settlementRepository,
                                 MilestoneRepository milestoneRepository,
                                 DealRepository dealRepository,
                                 EscrowAccountRepository escrowAccountRepository,
                                 EscrowLedgerRepository escrowLedgerRepository,
                                 LedgerService ledgerService) {
        this.settlementRepository = settlementRepository;
        this.milestoneRepository = milestoneRepository;
        this.dealRepository = dealRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public SettlementInstruction simulateSettlement(UUID dealId, UUID milestoneId, UUID buyerId,
                                                    UUID vendorOrgId, UUID bankAccountId,
                                                    BigDecimal amount, String currency, String utr) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new IllegalArgumentException("Deal not found: " + dealId));

        String settlementRef = String.format("FINX-SET-TEST-%d-%06d",
                java.time.Year.now().getValue(), SEQ.incrementAndGet());

        SettlementInstruction instruction = new SettlementInstruction(
                settlementRef,
                dealId,
                milestoneId,
                vendorOrgId,
                bankAccountId,
                amount,
                currency,
                "MOCK",
                "TEST-IDEMP-" + SEQ.get(),
                buyerId
        );
        instruction.setProviderSettlementId("MOCK-STL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        instruction.setUtrNumber(utr);
        instruction.setStatus(SettlementStatus.SETTLED);
        instruction.setSettledAt(Instant.now());

        SettlementInstruction savedInstruction = settlementRepository.saveAndFlush(instruction);

        // Deduct escrow
        EscrowAccount account = escrowAccountRepository.findByDealId(dealId)
                .orElseThrow(() -> new IllegalArgumentException("Escrow account not found for deal: " + dealId));
        account.setBalance(account.getBalance().subtract(amount).max(BigDecimal.ZERO));
        account.setTotalReleased(account.getTotalReleased().add(amount));
        escrowAccountRepository.saveAndFlush(account);

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                null,
                milestoneId,
                TransactionType.RELEASE,
                amount,
                account.getBalance(),
                "Test fixture simulated settlement release"
        );
        escrowLedgerRepository.saveAndFlush(ledger);

        // Update milestone status
        milestone.setStatus(MilestoneStatus.COMPLETED);
        milestoneRepository.saveAndFlush(milestone);

        // Update deal status if all completed
        List<Milestone> allDealMilestones = milestoneRepository.findByDealIdOrderBySequenceAsc(dealId);
        boolean allCompleted = !allDealMilestones.isEmpty() &&
                allDealMilestones.stream().allMatch(m -> m.getStatus() == MilestoneStatus.COMPLETED);
        if (allCompleted) {
            deal.setStatus(DealStatus.COMPLETED);
            dealRepository.saveAndFlush(deal);
        }

        // Post ledger double-entry
        PostJournalEntryRequest journalReq = new PostJournalEntryRequest(
                LedgerTransactionType.MILESTONE_SETTLEMENT,
                savedInstruction.getId(),
                dealId,
                milestoneId,
                currency,
                "Test settlement payout of " + amount + " " + currency + " (UTR: " + utr + ")",
                buyerId,
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.DEBIT, amount, "Escrow liability relieved on settlement"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.CREDIT, amount, "Escrow bank nodal funds disbursed to vendor")
                )
        );
        ledgerService.postJournalEntry(journalReq);

        return savedInstruction;
    }
}
