# FinX Bank-Partner KYC & KYB Verification Specification

> **Document Classification:** Confidential — Fintech Architecture & Compliance Brief  
> **Platform:** FinX Centralized B2B Milestone-Payment & Transaction Orchestration Platform  
> **Status:** Bank-Partner Ready (Centralized Fiat Escrow / Milestone Architecture)  
> **Applicable Environment:** Provider-Independent Verification Abstraction Layer

---

## Executive Summary & Regulatory Stance

FinX is a centralized B2B milestone-payment and transaction orchestration platform designed to streamline corporate trade, milestone escrow, and contract execution. 

**Important Architectural Boundaries:**
- **No Blockchain / Crypto:** FinX uses no blockchain, tokens, cryptocurrencies, Polygon, or smart contracts.
- **No Razorpay / Escrow Pretense:** FinX does not pretend to hold an RBI escrow license or act as an unauthorized financial intermediary.
- **Separation of Verification & Settlement:** Real money movement will eventually be integrated with an RBI-approved, regulated banking partner. Until that partner integration is formally active, all real money movement remains disabled at the API and domain layers (`BANKING_PARTNER_NOT_CONFIGURED`).
- **Provider Independence:** All verification checks (individual KYC, corporate KYB, penny drop bank verification) operate behind a clean provider-agnostic interface (`VerificationProvider`).

---

## 1. What FinX Currently Verifies

FinX conducts automated verification across three distinct levels: Individual KYC, Corporate KYB, and Banking Instruments.

| Domain | Check Type | Verification Target | Authoritative Source / Registry |
| :--- | :--- | :--- | :--- |
| **Individual KYC** | PAN Verification | Operating individual identity & status | NSDL / Income Tax Department (ITD) |
| **Individual KYC** | DigiLocker / eKYC | Name, DOB, address, masked Aadhaar reference | UIDAI via DigiLocker Session |
| **Individual KYC** | Name & DOB Matching | Algorithmic fuzzy & phonetic comparison | FinX Normalized Name Match Engine |
| **Corporate KYB** | Business PAN | Entity tax identification & category | NSDL / ITD database |
| **Corporate KYB** | GSTIN Verification | Active status, legal name, trade name, address | Goods and Services Tax Network (GSTN) |
| **Corporate KYB** | Corporate Registration | CIN (Pvt Ltd / Ltd), LLPIN (LLP), Udyam (MSME) | Ministry of Corporate Affairs (MCA) / MSME |
| **Corporate KYB** | Authorized Signatory | Signatory authority delegation & individual KYC | FinX Signatory Delegation Registry |
| **Corporate KYB** | Beneficial Owners | Significant beneficial owners (>10% or >25%) | Declared ownership registry |
| **Bank Verification**| Penny Drop Validation | Account existence & registered beneficiary name | NPCI IMPS / Beneficiary Bank CBS |
| **Bank Verification**| Account Name Match | Corporate legal name vs CBS account holder name | FinX Token-Normalized Name Match Engine |

---

## 2. Verification Providers & Architecture

FinX isolates all provider-specific network protocols and payload contracts behind an adapter interface:

```
                  +--------------------------------+
                  |         FinX Platform          |
                  |  (VerificationService & Domain)|
                  +---------------+----------------+
                                  |
                                  v
                  +--------------------------------+
                  |      VerificationProvider      |
                  |          (Interface)           |
                  +-------+--------------+---------+
                          |              |
           +--------------+              +--------------+
           v                                            v
+-----------------------+                    +-----------------------+
| MockVerification      |                    | SetuVerification      |
| Provider (Sandbox)    |                    | Provider (QA / Prod)  |
+-----------------------+                    +-----------------------+
           |                                            |
           v                                            v
Deterministic local mocks                      Upstream Setu API Rails
for CI/CD and integration                      (MTLS / HMAC / WebClient)
```

- **Active Provider Configuration:** Controlled via `VERIFICATION_PROVIDER=mock` (or `setu` / `future_partner`).
- **Sandbox Marking:** The mock provider explicitly identifies itself as sandbox (`isSandbox=true`). Responses and UI badges clearly indicate `MOCK / SANDBOX VERIFIED BUSINESS` to prevent false confidence.
- **Fail-Safe Operation:** Timeouts, downstream outages, or provider format changes default to `FAILED` or `PENDING_REVIEW`—never `VERIFIED`.

---

## 3. Data Storage & Data Minimization

FinX strictly adheres to the principle of data minimization and complies with Indian Digital Personal Data Protection (DPDP) standards:

### Data FinX Stores
- **Masked Identifiers:** Masked PAN (`ABCDE****F`), Masked Bank Account (`********1234`), Masked Aadhaar reference (`XXXX-XXXX-1234`).
- **Encrypted Account Numbers:** Stored using AES-256-GCM authenticated encryption at rest (`PiiEncryptionService`) with random IVs.
- **Hashed Identifiers:** SHA-256 one-way hashes used for uniqueness checks (e.g., preventing duplicate bank account registrations).
- **Match Scores:** Algorithmic similarity scores (0.0 to 100.0) and match status (`MATCH`, `PARTIAL_MATCH`, `MISMATCH`, `MANUAL_REVIEW`).
- **Consent Records:** Timestamped user consents with purpose, version, client IP address, and user agent.
- **Audit Events:** Append-only verification log (`VerificationEvent`) recording actor ID, provider reference, previous status, new status, and reason.

### Data FinX Does NOT Store
- ❌ **Full Unmasked Aadhaar Numbers:** Full 12-digit Aadhaar numbers are never stored in databases or memory dumps.
- ❌ **Biometric Data:** No raw fingerprint, iris, or facial biometric templates are ever captured or retained.
- ❌ **Bank Login Credentials:** No net-banking passwords, debit card PINs, OTPs, or CVVs.
- ❌ **Provider Secret Keys:** Upstream API keys and client secrets reside exclusively in environment variables and are never persisted in databases or logs.

---

## 4. Consent Process & State Machines

### 4.1 Verification Consent
No external verification call (PAN, GSTIN, Bank Penny Drop, or Identity Session) can execute without an explicit, verifiable consent record stored in `verification_consents`. If consent is missing, the backend throws an immediate `400 Bad Request`.

```
User Action --> View Legal Notice --> Opt-in Checkbox --> POST /api/v1/kyc/consent
                                                                  |
                                                                  v
                                                     VerificationConsent Entity Saved
                                                                  |
                                                                  v
                                                      Allow External Check
```

### 4.2 KYC State Machine (Individual)
Individual KYC tracks operating personnel (Proprietor, Director, Authorized Signatory, Finance Approver):

```
NOT_STARTED
    |
    v
CONSENT_REQUIRED  (Mandatory consent opt-in)
    |
    v
IN_PROGRESS       (PAN submitted / DigiLocker initiated)
    |
    +---> PENDING_PROVIDER (Waiting for upstream callback / session completion)
    |
    +---> PENDING_REVIEW   (Name partial match: 70-84%; flagged for compliance officer)
    |
    +---> VERIFIED         (PAN valid + Identity verified + Name match >= 85%)
    |
    +---> FAILED           (Invalid PAN / identity rejected)
    |
    +---> REJECTED         (Compliance officer rejection)
    |
    +---> EXPIRED          (Annual re-KYC expiration)
```

### 4.3 KYB State Machine (Corporate)
Corporate verification verifies legal entity existence and authority:

```
NOT_STARTED
    |
    v
IN_PROGRESS       (Business PAN + GSTIN + Registration entered)
    |
    +---> Conditional Checks Evaluated:
    |       - PRIVATE_LIMITED: PAN + GSTIN + CIN + Signatory + Bank
    |       - LLP:             PAN + GSTIN + LLPIN + Signatory + Bank
    |       - PROPRIETORSHIP:  PAN + Proprietor KYC + Bank (GSTIN optional)
    |
    +---> PENDING_REVIEW   (Name mismatch in bank account or GST trade name)
    |
    +---> VERIFIED         (All required checks satisfied server-side)
    |
    +---> FAILED           (Inactive registration / invalid tax ID)
```

---

## 5. Authorized Signatory & Beneficial Owner Model

### 5.1 Authorized Representative
FinX explicitly separates human existence from corporate authorization:
- **Human Verification:** Confirms identity via individual KYC.
- **Authority Delegation:** Stored in `authorized_representatives`:
  - Relationship Types: `OWNER`, `PROPRIETOR`, `PARTNER`, `DIRECTOR`, `AUTHORIZED_SIGNATORY`, `FINANCE_APPROVER`.
  - Authority Statuses: `PENDING`, `VERIFIED`, `REJECTED`, `REVOKED`.
  - An individual cannot sign agreements or activate corporate deals without both completed personal KYC and `VERIFIED` authority status.

### 5.2 Beneficial Owners
Stored in `beneficial_owners` to satisfy corporate transparency:
- Fields: `personName`, `ownershipPercentage`, `relationshipType`, `panMasked`, `verificationStatus`.
- Thresholds are configurable (defaulting to 10% or 25% based on partner policy).

---

## 6. Bank Account Penny Drop & Name Match Engine

### 6.1 Penny Drop Verification Flow
```
Corporate Admin Submits Account (Number, IFSC, Bank Name)
                          |
                          v
        Server-side Encryption (AES-256-GCM) & Hash
                          |
                          v
         Provider Penny Drop (Credit Re. 1.00)
                          |
                          v
    Core Banking Returns Registered Account Holder Name
                          |
                          v
            FinX Name Match Engine
(Token Jaccard + Jaro-Winkler + Levenshtein + Suffix Normalization)
                          |
             +------------+------------+
             |                         |
       Score >= 85%              Score < 85%
             |                         |
             v                         v
          VERIFIED               PENDING_REVIEW / FAILED
```

- **Browser Tamper Resistance:** The client browser is never allowed to assert verification status. Status transitions are strictly server-side.

---

## 7. Compliance Review & Auditability

### 7.1 Manual Review Process
When names partially match (e.g., legal aliases, abbreviations, or recent name changes) or providers return ambiguous codes:
- The profile transitions to `PENDING_REVIEW`.
- Compliance officers view evidence in `/admin/verifications`.
- Allowed Actions: `APPROVE_REVIEW`, `REJECT`, `REQUEST_INFORMATION`, `REVERIFY`.
- Mandatory inputs: Compliance officer actor ID, formal reason, notes.
- Provider verification evidence remains immutable; admin override decisions are tracked independently.

### 7.2 Immutable Audit Trail
Every transition generates an append-only `VerificationEvent`:
- Subject Type (`USER` or `ORGANIZATION`), Subject ID, Verification Type, Provider, Old Status, New Status, Actor, Timestamp, and safe metadata.

---

## 8. Platform Permission Gating

| Platform Action | Verification Requirement | Enforcement Method |
| :--- | :--- | :--- |
| **Registration / Login** | Account verified | Spring Security JWT |
| **View Dashboard** | Authentication only | RBAC |
| **Create Draft Deal** | Authentication only | Deal Service |
| **Activate Deal** | Business KYB (`VERIFIED`) | `VerificationEligibilityService` |
| **Sign Legal Agreement** | Individual KYC + `VERIFIED` Signatory Authority | `VerificationEligibilityService` |
| **Fund Real Transaction** | Regulated Banking Partner Integration Required | Blocked (`BANKING_PARTNER_NOT_CONFIGURED`) |
| **Receive Real Settlement**| Regulated Banking Partner Integration Required | Blocked (`BANKING_PARTNER_NOT_CONFIGURED`) |

---

## 9. BANK PARTNER REQUIREMENTS — TO BE CONFIRMED

The following section defines open parameters to be finalized during formal partner integration workshops with the selected regulated banking/escrow institution:

### 9.1 Partner-Specific KYC & Identity Requirements
- [ ] Direct CKYC (Central KYC Registry) search integration requirements
- [ ] Video KYC (V-KYC) requirement for high-value transactors (> INR 50 Lakhs)
- [ ] Liveness detection and geolocation bounding standards

### 9.2 Partner-Specific KYB & Entity Requirements
- [ ] Requirement of certified Board Resolution vs digital declaration
- [ ] Partnership Deed / MoA / AoA upload validation requirements
- [ ] LEI (Legal Entity Identifier) capture for transactions > INR 50 Crores

### 9.3 Beneficial Ownership & PEP Rules
- [ ] Beneficial ownership threshold: 10% (strict) vs 25% (standard)
- [ ] Politically Exposed Persons (PEP) screening list provider & frequency
- [ ] Sanctions screening (UN / OFAC / FATF / MHA) automation parameters

### 9.4 Account & Settlement Rails Architecture
- [ ] Dedicated Virtual Account Number (VAN) routing prefix
- [ ] Escrow / Nodal account structure under RBI guidelines
- [ ] Real-time collection webhooks & MT940 end-of-day bank statement formats
- [ ] RTGS / NEFT / IMPS automated batch settlement schedules

### 9.5 Risk & Transaction Limits
- [ ] Maximum daily milestone funding limit per unrated enterprise
- [ ] Velocity limits on new counterparty milestone payments
- [ ] Transaction monitoring rules and suspicious activity reporting (STR/SAR)

### 9.6 Document Retention & Re-KYC Cycles
- [ ] Document retention horizon (5 years post account closure under PMLA)
- [ ] Periodic Re-KYC schedule: Low risk (10 years), Medium risk (8 years), High risk (2 years)
- [ ] Ongoing automated trigger-based review on MCA status changes

---

*FinX Verification Layer Architecture — Designed for High-Assurance Corporate Trust & Effortless Banking Interoperability.*
