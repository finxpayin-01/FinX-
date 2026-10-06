package com.finx.reconciliation.repository;

import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, UUID> {

    Optional<BankStatementImport> findByProviderAndProviderAccountIdAndSourceHash(
            String provider, String providerAccountId, String sourceHash);

    List<BankStatementImport> findByProviderAndProviderAccountIdAndWindowStartAndWindowEnd(
            String provider, String providerAccountId, Instant windowStart, Instant windowEnd);

    List<BankStatementImport> findByStatus(BankStatementImportStatus status);
}
