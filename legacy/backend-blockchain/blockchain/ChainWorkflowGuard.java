package com.finx.blockchain;

import com.finx.exception.BadRequestException;
import com.finx.payment.config.PaymentRailPolicy;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.finx.blockchain.ChainStore.*;

@Service
public class ChainWorkflowGuard {
    private final ChainStore store;
    private final PaymentRailPolicy policy;
    public ChainWorkflowGuard(ChainStore store,PaymentRailPolicy policy) { this.store=store;this.policy=policy; }
    public void mutableSchedule(UUID deal) {
        if (store.exists(deal)) throw new BadRequestException("Escrow allocation schedule is frozen");
    }
    public void fiatOnly(UUID deal) {
        if (store.exists(deal)) throw new BadRequestException("This project is permanently assigned to blockchain escrow");
    }
    public void funded(UUID deal) {
        if (!policy.blockchain() && !store.exists(deal)) return;
        if (!store.exists(deal)) throw new BadRequestException("Confirmed blockchain funding is required before work starts");
        var escrow=store.escrow(deal);
        if (!str(escrow,"reconciliation_status").equals("MATCHED") || units(escrow,"funded_atomic").signum()==0
                || !java.util.List.of("FUNDED","ACTIVE").contains(str(escrow,"status")))
            throw new BadRequestException("Escrow is not funded or is disputed/pending reconciliation");
    }
    public void resolution(UUID deal,UUID milestone,String reason) {
        if (!store.exists(deal)) return;
        if (reason==null || reason.isBlank()) throw new BadRequestException("A resolution reason is required");
        var escrow=store.escrow(deal);
        var allocations=store.allocations(uuid(escrow,"id"));
        if (!str(escrow,"reconciliation_status").equals("MATCHED") || allocations.stream()
                .filter(a -> milestone==null || uuid(a,"milestone_id").equals(milestone))
                .anyMatch(a -> !java.util.List.of("4","6").contains(str(a,"chain_status"))))
            throw new BadRequestException("Confirm authorized on-chain resolution before closing the dispute case");
    }
    public com.finx.deal.entity.DealStatus resolvedProjectStatus(UUID deal) {
        if (!store.exists(deal)) return com.finx.deal.entity.DealStatus.ACCEPTED;
        return switch(str(store.escrow(deal),"status")) {
            case "COMPLETED" -> com.finx.deal.entity.DealStatus.COMPLETED;
            case "REFUNDED", "CANCELLED" -> com.finx.deal.entity.DealStatus.CANCELLED;
            case "DISPUTED" -> com.finx.deal.entity.DealStatus.DISPUTED;
            default -> com.finx.deal.entity.DealStatus.IN_PROGRESS;
        };
    }
}
