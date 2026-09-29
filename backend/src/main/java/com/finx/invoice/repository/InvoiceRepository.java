package com.finx.invoice.repository;

import com.finx.invoice.entity.Invoice;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByMilestoneIdOrderByCreatedAtDesc(UUID milestoneId);

    List<Invoice> findByDealIdOrderByCreatedAtDesc(UUID dealId);

    Optional<Invoice> findFirstByMilestoneIdOrderByCreatedAtDesc(UUID milestoneId);

    List<Invoice> findByDocumentHash(String documentHash);

    List<Invoice> findByVendorOrganizationIdAndNormalizedInvoiceNumber(UUID vendorOrganizationId, String normalizedInvoiceNumber);

    List<Invoice> findByNormalizedInvoiceNumberAndTotalAmount(String normalizedInvoiceNumber, BigDecimal totalAmount);

    long countByStatus(InvoiceStatus status);

    long countByMatchingStatus(MatchingStatus matchingStatus);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.duplicateConfidence <> 'UNIQUE'")
    long countDuplicatesDetected();

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.status = 'REVIEW_REQUIRED' OR EXISTS (SELECT r FROM InvoiceReview r WHERE r.invoice = i AND r.status IN ('OPEN', 'IN_REVIEW'))")
    long countPendingReviews();

    List<Invoice> findAllByOrderByCreatedAtDesc();
    List<Invoice> findTop20ByOrderByCreatedAtDesc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id=:id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);
    @Query("select i from Invoice i where i.documentHash=:hash or i.normalizedInvoiceNumber=:number")
    List<Invoice> findDuplicateCandidates(@Param("hash") String hash, @Param("number") String number);
}
