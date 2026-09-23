package com.finx.org.repository;

import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationBankAccountRepository extends JpaRepository<OrganizationBankAccount, UUID> {
    List<OrganizationBankAccount> findByOrganizationId(UUID organizationId);
    Optional<OrganizationBankAccount> findByOrganizationIdAndIsPrimaryTrue(UUID organizationId);
    Optional<OrganizationBankAccount> findByOrganizationIdAndVerificationStatus(UUID organizationId, BankVerificationStatus status);
    boolean existsByAccountNumberHash(String accountNumberHash);
}
