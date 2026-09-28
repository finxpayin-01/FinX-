package com.finx.trust.repository;

import com.finx.trust.entity.TrustEvent;
import com.finx.trust.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
import java.time.Instant;

public interface TrustEventRepository extends JpaRepository<TrustEvent, UUID> {
    boolean existsBySourceKey(String key);
    @Query("select e from TrustEvent e where e.organizationId=:id order by e.occurredAt,e.id")
    @QueryHints(@QueryHint(name="org.hibernate.fetchSize", value="250"))
    java.util.stream.Stream<TrustEvent> streamForOrganization(@Param("id") UUID id);
    List<TrustEvent> findByOrganizationIdOrderByOccurredAtDesc(UUID id, Pageable page);
    Optional<TrustEvent> findFirstByOrganizationIdAndProcessedFalseOrderByCreatedAtDesc(UUID id);
    @Modifying
    @Query("update TrustEvent e set e.processed=true where e.organizationId=:id and e.processed=false")
    int markProcessed(@Param("id") UUID id);
}
