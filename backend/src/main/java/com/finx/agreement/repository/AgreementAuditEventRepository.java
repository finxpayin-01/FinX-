package com.finx.agreement.repository;

import com.finx.agreement.entity.AgreementAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgreementAuditEventRepository extends JpaRepository<AgreementAuditEvent, UUID> {
    List<AgreementAuditEvent> findByAgreementIdOrderByTimestampAsc(UUID agreementId);
}
