package com.finx.trust.repository;

import com.finx.trust.entity.TrustOrganization;
import com.finx.trust.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
import java.time.Instant;

public interface TrustOrganizationRepository extends JpaRepository<TrustOrganization, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from TrustOrganization o where o.id=:id")
    Optional<TrustOrganization> lock(@Param("id") UUID id);
    @Query("select o.id from TrustOrganization o where (o.retryAfter is null or o.retryAfter<=:now) and (o.dirty=true or o.lastCalculatedAt is null or o.lastCalculatedAt<:stale or o.configVersion<:version) order by o.lastCalculatedAt asc, o.id asc")
    List<UUID> pending(@Param("now") Instant now, @Param("stale") Instant stale, @Param("version") long version, Pageable page);
    @Query("select o from TrustOrganization o where lower(o.name) like lower(concat('%',:query,'%')) order by o.name,o.id")
    List<TrustOrganization> search(@Param("query") String query, Pageable page);
}
