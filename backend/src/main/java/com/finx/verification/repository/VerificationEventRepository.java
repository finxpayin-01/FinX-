package com.finx.verification.repository;

import com.finx.verification.entity.VerificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VerificationEventRepository extends JpaRepository<VerificationEvent, UUID> {
    List<VerificationEvent> findBySubjectTypeAndSubjectIdOrderByTimestampDesc(String subjectType, UUID subjectId);
    List<VerificationEvent> findAllByOrderByTimestampDesc();
}
