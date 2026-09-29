package com.finx.verification.repository;

import com.finx.verification.entity.VerificationCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, UUID> {
    List<VerificationCheck> findByEntityTypeAndEntityIdOrderByCheckedAtDesc(String entityType, UUID entityId);
}
