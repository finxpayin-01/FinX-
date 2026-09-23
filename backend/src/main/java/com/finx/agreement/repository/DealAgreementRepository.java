package com.finx.agreement.repository;

import com.finx.agreement.entity.DealAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DealAgreementRepository extends JpaRepository<DealAgreement, UUID> {
    Optional<DealAgreement> findTopByDealIdOrderByVersionDesc(UUID dealId);
    Optional<DealAgreement> findByDealIdAndVersion(UUID dealId, int version);
    List<DealAgreement> findByDealIdOrderByVersionDesc(UUID dealId);
}
