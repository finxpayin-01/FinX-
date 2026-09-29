package com.finx.invoice.repository;

import com.finx.invoice.entity.InvoiceExtractedField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvoiceExtractedFieldRepository extends JpaRepository<InvoiceExtractedField, UUID> {
    List<InvoiceExtractedField> findByInvoiceId(UUID invoiceId);
    void deleteByInvoiceId(UUID invoiceId);
}
