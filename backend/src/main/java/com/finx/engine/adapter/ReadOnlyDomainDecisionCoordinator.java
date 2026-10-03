package com.finx.engine.adapter;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.engine.service.EngineDecisionService;
import com.finx.exception.ResourceNotFoundException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.risk.dto.RiskDtos.UserType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Lightweight evaluation coordinator to evaluate Buyer Risk, Vendor Risk,
 * and Document Invoice under a shared evaluationId and correlationId.
 * <p>
 * STRICT SAFETY BOUNDARY:
 * - Evaluates individual domains independently
 * - NEVER aggregates decisions into RELEASE
 * - NEVER combines statuses into a composite boolean or weighted total score
 * - NEVER makes transaction authorization decisions
 * - Zero banking calls, zero ledger modifications, zero escrow fund releases
 */
@Service
@Transactional(readOnly = true)
public class ReadOnlyDomainDecisionCoordinator {

    private final RiskDecisionAdapter riskAdapter;
    private final DocumentDecisionAdapter documentAdapter;
    private final DealRepository dealRepository;
    private final InvoiceRepository invoiceRepository;
    private final EngineDecisionService decisionService;

    public ReadOnlyDomainDecisionCoordinator(
            RiskDecisionAdapter riskAdapter,
            DocumentDecisionAdapter documentAdapter,
            DealRepository dealRepository,
            InvoiceRepository invoiceRepository,
            EngineDecisionService decisionService
    ) {
        this.riskAdapter = Objects.requireNonNull(riskAdapter, "riskAdapter must not be null");
        this.documentAdapter = Objects.requireNonNull(documentAdapter, "documentAdapter must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
        this.decisionService = Objects.requireNonNull(decisionService, "decisionService must not be null");
    }

    /**
     * Evaluates domain decisions for a deal (Buyer Risk, Vendor Risk, and Invoice Document)
     * under a single evaluationId and correlationId without performing persistence.
     */
    public DomainEvaluationResult evaluateDeal(UUID dealId, UUID invoiceId, UUID evaluationId, UUID correlationId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        UUID evalId = evaluationId != null ? evaluationId : UUID.randomUUID();
        UUID corrId = correlationId != null ? correlationId : UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Buyer Risk Command (subject: buyer organization)
        EvaluationContext buyerContext = new EvaluationContext(
                evalId,
                deal.getBuyerOrganizationId(),
                corrId,
                DecisionSubjectType.ORGANIZATION.name(),
                deal.getBuyerOrganizationId(),
                now
        );
        RecordEngineDecisionCommand buyerRiskCommand = riskAdapter.evaluateForDeal(deal, UserType.BUYER, buyerContext);

        // 2. Vendor Risk Command (subject: vendor organization)
        RecordEngineDecisionCommand vendorRiskCommand = null;
        if (deal.getSellerOrganizationId() != null) {
            EvaluationContext vendorContext = new EvaluationContext(
                    evalId,
                    deal.getSellerOrganizationId(),
                    corrId,
                    DecisionSubjectType.ORGANIZATION.name(),
                    deal.getSellerOrganizationId(),
                    now
            );
            vendorRiskCommand = riskAdapter.evaluateForDeal(deal, UserType.VENDOR, vendorContext);
        }

        // 3. Document Invoice Command (subject: invoice)
        RecordEngineDecisionCommand documentCommand = null;
        Invoice invoice = null;
        if (invoiceId != null) {
            invoice = invoiceRepository.findById(invoiceId).orElse(null);
        } else {
            List<Invoice> invoices = invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId);
            if (!invoices.isEmpty()) {
                invoice = invoices.getFirst();
            }
        }

        if (invoice != null) {
            EvaluationContext docContext = new EvaluationContext(
                    evalId,
                    deal.getBuyerOrganizationId(),
                    corrId,
                    DecisionSubjectType.INVOICE.name(),
                    invoice.getId(),
                    now
            );
            documentCommand = documentAdapter.evaluateInvoice(invoice, docContext);
        }

        return new DomainEvaluationResult(evalId, corrId, buyerRiskCommand, vendorRiskCommand, documentCommand);
    }

    /**
     * Evaluates and records domain decisions into the engine_decisions repository.
     * Enforces idempotent replay and returns the persisted EngineDecision records.
     */
    @Transactional
    public List<EngineDecision> evaluateAndRecordDeal(UUID dealId, UUID invoiceId, UUID evaluationId, UUID correlationId) {
        DomainEvaluationResult result = evaluateDeal(dealId, invoiceId, evaluationId, correlationId);
        List<EngineDecision> recorded = new ArrayList<>();

        if (result.buyerRiskCommand() != null) {
            recorded.add(decisionService.recordDecision(result.buyerRiskCommand()));
        }
        if (result.vendorRiskCommand() != null) {
            recorded.add(decisionService.recordDecision(result.vendorRiskCommand()));
        }
        if (result.documentCommand() != null) {
            recorded.add(decisionService.recordDecision(result.documentCommand()));
        }

        return Collections.unmodifiableList(recorded);
    }
}
