package com.finx.invoice.repository;

import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.InvoiceReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceReviewRepository extends JpaRepository<InvoiceReview, UUID> {
    List<InvoiceReview> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);
    Optional<InvoiceReview> findFirstByInvoiceIdAndStatus(UUID invoiceId, InvoiceReviewStatus status);
}
