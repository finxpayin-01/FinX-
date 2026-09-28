package com.finx.trust.repository;

import com.finx.trust.entity.TrustManualReview;
import com.finx.trust.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.*;
import java.util.*;
import java.time.Instant;

public interface TrustManualReviewRepository extends JpaRepository<TrustManualReview, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from TrustManualReview r where r.id=:id")
    Optional<TrustManualReview> lock(@Param("id") UUID id);
    List<TrustManualReview> findByStatusOrderByCreatedAtDesc(String status, Pageable page);
}
