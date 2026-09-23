package com.finx.org.enums;

public class OrgEnums {

    public enum EntityType {
        PROPRIETORSHIP,
        PARTNERSHIP,
        LLP,
        PRIVATE_LIMITED,
        PUBLIC_LIMITED,
        OTHER
    }

    public enum OrgVerificationStatus {
        NOT_STARTED,
        IN_PROGRESS,
        PENDING_REVIEW,
        VERIFIED,
        FAILED,
        REJECTED,
        EXPIRED
    }

    public enum OrgRole {
        OWNER,
        ADMIN,
        FINANCE,
        PROJECT_MANAGER,
        AUTHORIZED_SIGNATORY,
        MEMBER
    }

    public enum MemberStatus {
        INVITED,
        ACTIVE,
        SUSPENDED
    }

    public enum BankVerificationStatus {
        NOT_STARTED,
        IN_PROGRESS,
        VERIFIED,
        FAILED
    }
}
