package com.finx.verification.repository;

import com.finx.verification.entity.VerificationDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VerificationDocumentRepository extends JpaRepository<VerificationDocument, UUID> {
    List<VerificationDocument> findByEntityTypeAndEntityId(String entityType, UUID entityId);
}
