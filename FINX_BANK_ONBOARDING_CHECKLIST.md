# FinX Banking Partner Onboarding Checklist

This checklist tracks the multi-stage operational, legal, security, and technical onboarding process between FinX and the banking institution.

---

## 1. FINX MUST PROVIDE

### Legal & Corporate Governance
- [ ] Certificate of Incorporation / Business Registration Certificate.
- [ ] Memorandum & Articles of Association (MoA / AoA) *(subject to bank requirements)*.
- [ ] Permanent Account Number (PAN) card of legal entity.
- [ ] Goods and Services Tax (GSTIN) registration certificate.
- [ ] Board Resolution authorizing opening and operation of Nodal/Escrow pool account.
- [ ] List of authorized signatories, KYC documents, and specimen signatures *(subject to bank requirements)*.
- [ ] Ultimate Beneficial Ownership (UBO) declaration (>= 25% ownership/control).
- [ ] Anti-Money Laundering (AML) / Counter-Financing of Terrorism (CFT) policy document.

### Technical & Infrastructure
- [ ] FinX outbound static NAT IP addresses for API client whitelisting.
- [ ] Inbound HTTPS Webhook URL (FQDN with valid publicly trusted TLS certificate).
- [ ] CSR (Certificate Signing Request) for client mTLS authentication (if bank uses mutual TLS).
- [ ] Webhook receiver public key or shared secret coordination contact.
- [ ] Designated technical integration and operational escalation points of contact (24/7 runbook).

---

## 2. BANK MUST PROVIDE

### Commercial & Account Setup
- [ ] Formal Escrow / Nodal Account Agreement or Master Services Agreement (MSA).
- [ ] Dedicated Nodal/Escrow Account Number (`providerAccountId`) and IFSC.
- [ ] Account operational mandate (allowed debit rules, signatory dual controls).
- [ ] Schedule of transaction charges, clearing cut-offs (NEFT/RTGS/IMPS), and bank holidays.

### Sandbox / Integration Environment
- [ ] Sandbox API Gateway Base URL.
- [ ] Sandbox Client ID and Client Secret / API Key.
- [ ] Sandbox mTLS Root CA certificate and signed client certificate (or PKCS12 bundle).
- [ ] Sandbox Webhook HMAC shared secret or bank public key for signature verification.
- [ ] Simulated test bank accounts (whitelisted accounts for success, failure, delayed settlement).
- [ ] Formal API documentation and Swagger / OpenAPI 3.0 specification.
- [ ] Webhook header specification and signature algorithm specification.

### Production Environment Provisioning *(Post-UAT Signoff)*
- [ ] Production API Gateway Base URL.
- [ ] Production Client ID and Client Secret (delivered via secure out-of-band exchange).
- [ ] Production mTLS certificates and signed production credentials.
- [ ] Production webhook secret.
- [ ] Bank gateway IP address range for FinX firewall whitelisting.
- [ ] Daily automated statement delivery configuration (SFTP / S3 / API feed).

---

## 3. MUTUAL UAT SIGNOFF & PRODUCTION GATING

Before production payout traffic can be enabled (`finx.release.production-execution.enabled=true`), both parties must execute and certify the following milestones:

- [ ] **Stage 1: Connectivity & Security Handshake**:
  - Mutual TLS (mTLS) handshake verified.
  - IP whitelisting verified bidirectionally.
  - Test authentication token successfully acquired.
- [ ] **Stage 2: Payout Gateway Dispatch**:
  - Successful dispatch with stable idempotency key (`FINX-REL-{id}`).
  - Verification that duplicate dispatch returns cached result without duplicate transfer.
- [ ] **Stage 3: Webhook Verification**:
  - Receipt of bank webhook on FinX staging endpoint.
  - HMAC-SHA256 / RSA cryptographic signature verification passed.
  - Replay protection verified (duplicate webhook processed idempotently).
- [ ] **Stage 4: Status Recovery & Timeout Resilience**:
  - Simulated gateway timeout recovered via status inquiry API.
  - Rejection / failure reason code properly mapped to FinX state machine.
- [ ] **Stage 5: Three-Way Statement Reconciliation**:
  - Ingestion of end-of-day bank statement feed.
  - Deterministic matching by UTR, amount, currency, and provider account verified (100% matched, 0 discrepancies).
- [ ] **Stage 6: Formal UAT Matrix Completion**:
  - All 15 test cases in `FINX_BANK_UAT_TEST_MATRIX.md` marked as `PASS`.
- [ ] **Stage 7: Production Gate Signoff**:
  - FinX Engineering Lead Signoff.
  - FinX Compliance / Finance Signoff.
  - Bank Integration Lead Signoff.
  - Bank Relationship Manager / Operations Signoff.
