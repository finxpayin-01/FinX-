package com.finx.reconciliation.repository;

import com.finx.reconciliation.entity.BankStatementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {

    List<BankStatementLine> findByImportIdOrderByTransactionTimeAscIdAsc(UUID importId);

    List<BankStatementLine> findByProviderAndProviderAccountIdAndTransactionTimeBetween(
            String provider, String providerAccountId, Instant start, Instant end);

    Optional<BankStatementLine> findByProviderAndProviderAccountIdAndProviderLineId(
            String provider, String providerAccountId, String providerLineId);

    Optional<BankStatementLine> findByProviderAndProviderAccountIdAndLineFingerprint(
            String provider, String providerAccountId, String lineFingerprint);
}
