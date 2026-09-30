package com.finx.escrow;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * TEST-ONLY fixture under src/test to establish released escrow state for downstream integration tests
 * without relying on the disabled direct release endpoint.
 * This class is not packaged into production builds.
 */
@TestComponent
public class TestEscrowReleaseFixture {

    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealRepository dealRepository;

    public TestEscrowReleaseFixture(EscrowAccountRepository escrowAccountRepository,
                                    EscrowLedgerRepository escrowLedgerRepository,
                                    MilestoneRepository milestoneRepository,
                                    DealRepository dealRepository) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
        this.milestoneRepository = milestoneRepository;
        this.dealRepository = dealRepository;
    }

    @Transactional
    public void simulateEscrowRelease(UUID milestoneId, String comment) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new IllegalArgumentException("Deal not found: " + milestone.getDealId()));

        EscrowAccount account = escrowAccountRepository.findByDealId(deal.getId())
                .orElseThrow(() -> new IllegalArgumentException("Escrow account not found for deal: " + deal.getId()));

        BigDecimal amount = milestone.getAmount();
        BigDecimal newBalance = account.getBalance().subtract(amount);
        account.setBalance(newBalance.max(BigDecimal.ZERO));
        account.setTotalReleased(account.getTotalReleased().add(amount));
        escrowAccountRepository.saveAndFlush(account);

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                null,
                milestone.getId(),
                TransactionType.RELEASE,
                amount,
                account.getBalance(),
                comment != null ? comment : "Test fixture simulated escrow release"
        );
        escrowLedgerRepository.saveAndFlush(ledger);

        milestone.setStatus(MilestoneStatus.COMPLETED);
        milestoneRepository.saveAndFlush(milestone);

        List<Milestone> allDealMilestones = milestoneRepository.findByDealIdOrderBySequenceAsc(deal.getId());
        boolean allCompleted = !allDealMilestones.isEmpty() &&
                allDealMilestones.stream().allMatch(m -> m.getStatus() == MilestoneStatus.COMPLETED);
        if (allCompleted) {
            deal.setStatus(DealStatus.COMPLETED);
            dealRepository.saveAndFlush(deal);
        }
    }
}
