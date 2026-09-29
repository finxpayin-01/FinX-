package com.finx.invoice.repository;
import com.finx.invoice.entity.InvoiceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface InvoiceDocumentRepository extends JpaRepository<InvoiceDocument, UUID> {}
