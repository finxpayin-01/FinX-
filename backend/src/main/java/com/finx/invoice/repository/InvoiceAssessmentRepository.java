package com.finx.invoice.repository;
import com.finx.invoice.entity.InvoiceAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface InvoiceAssessmentRepository extends JpaRepository<InvoiceAssessment, UUID> {
    List<InvoiceAssessment> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
    Optional<InvoiceAssessment> findFirstByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);
}
