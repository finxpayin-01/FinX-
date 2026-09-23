package com.finx.org.entity;

import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_bank_accounts")
public class OrganizationBankAccount {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "account_holder_name", nullable = false)
    private String accountHolderName;

    @Column(name = "account_number_masked", nullable = false)
    private String accountNumberMasked;

    @Column(name = "account_number_hash", nullable = false)
    private String accountNumberHash;

    @Column(name = "ifsc_code", nullable = false)
    private String ifscCode;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private BankVerificationStatus verificationStatus;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OrganizationBankAccount() {
        this.id = UUID.randomUUID();
        this.isPrimary = false;
        this.verificationStatus = BankVerificationStatus.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public OrganizationBankAccount(UUID organizationId, String accountHolderName, String accountNumberMasked,
                                   String accountNumberHash, String ifscCode, String bankName, String branchName, boolean isPrimary) {
        this();
        this.organizationId = organizationId;
        this.accountHolderName = accountHolderName;
        this.accountNumberMasked = accountNumberMasked;
        this.accountNumberHash = accountNumberHash;
        this.ifscCode = ifscCode;
        this.bankName = bankName;
        this.branchName = branchName;
        this.isPrimary = isPrimary;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public void setAccountNumberMasked(String accountNumberMasked) { this.accountNumberMasked = accountNumberMasked; }
    public String getAccountNumberHash() { return accountNumberHash; }
    public void setAccountNumberHash(String accountNumberHash) { this.accountNumberHash = accountNumberHash; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public boolean isPrimary() { return isPrimary; }
    public void setPrimary(boolean primary) { isPrimary = primary; }
    public BankVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(BankVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getVerificationReference() { return verificationReference; }
    public void setVerificationReference(String verificationReference) { this.verificationReference = verificationReference; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
