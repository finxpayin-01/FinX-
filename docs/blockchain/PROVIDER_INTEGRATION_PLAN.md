# Provider integration plan

No production provider, credentials, approval or real transactions in this phase. Interfaces/adapters are later-phase work.

Create OnRampProvider (quote/session/status/verify raw webhook/reconcile), OffRampProvider (quote/payout/status/verify/reconcile), BankPayoutProvider, KYCProvider, KYBProvider, SanctionsScreeningProvider, TransactionMonitoringProvider, WalletService, SigningService and RpcProvider. Configuration selects compatible network/asset/jurisdiction; fail closed when absent. Mock implementations require explicit local/test profile and cannot produce production approvals.

On-ramp: CREATED, KYC_REQUIRED, PAYMENT_PENDING, PROCESSING, COMPLETED, FAILED, EXPIRED, REFUNDED. Off-ramp: REQUESTED, PROCESSING, CRYPTO_RECEIVED, FIAT_PROCESSING, PAID, FAILED, REVERSED. Compliance: NOT_STARTED, PENDING, VERIFIED, REJECTED, MANUAL_REVIEW, EXPIRED. Policy checks both businesses, bank verification, limits and holds.

Persist quotes with fiat/crypto amount, decimals, FX, expiration and platform/ramp/network/tax breakdown. Require explicit fee consent; do not reduce vendor allocations. Inbox verifies raw signature, provider-specific timestamp, event uniqueness, customer/reference/amount/currency/chain/token. Encrypt raw events, redact logs, rate-limit and apply provider-supported IP filtering. Reordered/unknown events enter reconciliation.

V1 requires delivery to verified buyer wallet, then buyer-authorized allowance and fundEscrow(total). Direct-to-escrow transfer is unsupported. Provider success completes conversion; only canonical finalized EscrowFunded proves custody. Fixed vendor receives release. Separate vendor authorization transfers to off-ramp; on-chain release never means INR settled.

| Failure | Recovery |
| --- | --- |
| INR collected/token missing | Same-intent provider query, reconciliation/hold, never fabricated funding |
| Broadcast timeout | Persist signed hash/nonce; query/rebroadcast same bytes; replacement lineage |
| Missed mined event/DB crash | Durable polling cursor, idempotent projector/journal |
| Crypto received/bank failed | Keep released state, failed payout/review, provider retry/return protocol |
| Reorg/provider mismatch | Halt dependent payout, replay observations, compensating journal |
| Quote expiry/gas spike/stuck nonce | Renew economic consent, bounded fees, same-intent replacement |

LEGAL_REVIEW_REQUIRED: actual custody/VDA-service boundary, INR payment permission, KYC/KYB/AML/sanctions, provider registration/jurisdiction, tax/TDS, retention, dispute enforceability and disclosures. Phase 11 dossier: INDIA_REGULATORY_GAP_ANALYSIS, VDA_SERVICE_PROVIDER_BOUNDARY, KYC_KYB_REQUIREMENTS, AML_TRANSACTION_MONITORING, CUSTODY_MODEL, ONRAMP_OFFRAMP_PROVIDER_REQUIREMENTS, TAX_AND_TDS_OPEN_QUESTIONS, LEGAL_REVIEW_REQUIRED. This is not a legal determination.
