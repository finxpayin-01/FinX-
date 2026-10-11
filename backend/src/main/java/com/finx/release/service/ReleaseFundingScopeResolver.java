package com.finx.release.service;

import com.finx.deal.entity.Deal;
import com.finx.milestone.entity.Milestone;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Derives FinX-controlled source bank/provider account scope strictly from
 * authoritatively funded payment evidence.
 * Pure read-only inspection of persisted PaymentInstruction and PaymentTransaction entities.
 * Strictly zero banking provider calls.
 */
@Service
@Transactional(readOnly = true)
public class ReleaseFundingScopeResolver {

    public static final String ERROR_SCOPE_MISSING = "RELEASE_FUNDING_SCOPE_MISSING";
    public static final String ERROR_SCOPE_AMBIGUOUS = "RELEASE_FUNDING_SCOPE_AMBIGUOUS";

    public record FundingScope(String provider, String providerAccountId, String currency) {}

    private final PaymentInstructionRepository paymentInstructionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public ReleaseFundingScopeResolver(
            PaymentInstructionRepository paymentInstructionRepository,
            PaymentTransactionRepository paymentTransactionRepository
    ) {
        this.paymentInstructionRepository = Objects.requireNonNull(paymentInstructionRepository, "paymentInstructionRepository must not be null");
        this.paymentTransactionRepository = Objects.requireNonNull(paymentTransactionRepository, "paymentTransactionRepository must not be null");
    }

    public FundingScope resolveFundingScope(Deal deal, Milestone milestone) {
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");

        List<PaymentInstruction> allInstructions = paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(deal.getId());

        Set<FundingScopeKey> distinctScopes = new LinkedHashSet<>();
        Set<String> distinctCurrencies = new LinkedHashSet<>();

        for (PaymentInstruction instruction : allInstructions) {
            // Must belong to deal
            if (!deal.getId().equals(instruction.getDealId())) {
                continue;
            }

            // Must match buyer organization
            if (deal.getBuyerOrganizationId() == null
                    || !deal.getBuyerOrganizationId().equals(instruction.getBuyerOrganizationId())) {
                continue;
            }

            // Milestone scoping: specific milestone OR whole-deal (null milestoneId)
            boolean isRelevantMilestone = instruction.getMilestoneId() == null
                    || instruction.getMilestoneId().equals(milestone.getId());
            if (!isRelevantMilestone) {
                continue;
            }

            // Must have at least one PaymentTransaction with status SUCCESS
            List<PaymentTransaction> transactions = paymentTransactionRepository.findByPaymentInstructionId(instruction.getId());
            boolean hasSuccessTx = false;
            for (PaymentTransaction tx : transactions) {
                if ("SUCCESS".equalsIgnoreCase(tx.getStatus())) {
                    // Check provider coherence between transaction and instruction if both present
                    if (tx.getProvider() != null && !tx.getProvider().isBlank()
                            && instruction.getProvider() != null && !instruction.getProvider().isBlank()) {
                        if (!tx.getProvider().trim().equalsIgnoreCase(instruction.getProvider().trim())) {
                            throw new IllegalStateException("PaymentTransaction provider '" + tx.getProvider()
                                    + "' does not match PaymentInstruction provider '" + instruction.getProvider() + "'");
                        }
                    }
                    hasSuccessTx = true;
                }
            }

            if (!hasSuccessTx) {
                continue;
            }

            // Validate non-blank provider, providerAccountId, currency
            String provider = instruction.getProvider();
            String providerAccountId = instruction.getProviderAccountId();
            String currency = instruction.getCurrency();

            if (provider == null || provider.trim().isBlank()) {
                throw new IllegalStateException("PaymentInstruction has blank provider: id=" + instruction.getId());
            }
            if (providerAccountId == null || providerAccountId.trim().isBlank()) {
                throw new IllegalStateException("PaymentInstruction has blank providerAccountId: id=" + instruction.getId());
            }
            if (currency == null || currency.trim().isBlank()) {
                throw new IllegalStateException("PaymentInstruction has blank currency: id=" + instruction.getId());
            }

            // Currency must be compatible with milestone currency
            String normInstrCurrency = currency.trim().toUpperCase();
            String normMilestoneCurrency = milestone.getCurrency().trim().toUpperCase();
            if (!normInstrCurrency.equals(normMilestoneCurrency)) {
                throw new IllegalStateException("Funding instruction currency '" + normInstrCurrency
                        + "' is incompatible with milestone currency '" + normMilestoneCurrency + "'");
            }

            distinctCurrencies.add(normInstrCurrency);
            distinctScopes.add(new FundingScopeKey(provider.trim().toUpperCase(), providerAccountId.trim()));
        }

        if (distinctScopes.isEmpty()) {
            throw new IllegalStateException(ERROR_SCOPE_MISSING + ": No successfully funded payment instructions found for deal "
                    + deal.getId() + " and milestone " + milestone.getId());
        }

        if (distinctScopes.size() > 1) {
            throw new IllegalStateException(ERROR_SCOPE_AMBIGUOUS + ": Multiple distinct funding scopes found ("
                    + distinctScopes.size() + ") for deal " + deal.getId() + " and milestone " + milestone.getId());
        }

        if (distinctCurrencies.size() > 1) {
            throw new IllegalStateException("Mixed funding currencies detected: " + distinctCurrencies);
        }

        FundingScopeKey single = distinctScopes.iterator().next();
        String resolvedCurrency = distinctCurrencies.iterator().next();
        return new FundingScope(single.provider(), single.providerAccountId(), resolvedCurrency);
    }

    private record FundingScopeKey(String provider, String providerAccountId) {}
}
