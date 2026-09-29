package com.finx.invoice.repository;

import com.finx.invoice.entity.InvoiceCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvoiceCheckRepository extends JpaRepository<InvoiceCheck, UUID> {
    List<InvoiceCheck> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
    void deleteByInvoiceId(UUID invoiceId);
}
