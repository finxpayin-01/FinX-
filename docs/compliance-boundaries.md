# Compliance & Regulatory Boundaries

In the B2B fiat-to-crypto escrow workflow, FinX explicitly acts as a technology facilitation provider, **not** as a regulated financial custodian or money transmitter. The movement of fiat value to crypto, custody logic, and payouts are deliberately routed through appropriately licensed third-party providers.

## Responsibilities Matrix

### 1. FinX (The Platform)
- **Provides Software Infrastructure:** Escrow logic orchestration, B2B deal negotiation interface, milestone completion sign-offs.
- **Smart Contract Operation:** Deploys and manages the EVM-based smart contracts that programmatically lock/release funds based on approved digital signatures from milestones.
- **Security & Authorization:** Identity access management (RBAC), multi-factor authentication (MFA).
- **Does NOT:** Hold user fiat, convert fiat to digital assets, act as a broker, or assume AML liability for fiat conversion.

### 2. On-Ramp Provider (e.g. Onramper, Ramp Network, Transak)
- **Fiat Custody & Conversion:** Legally receives INR from the Buyer.
- **KYC/KYB & AML:** Owns the onboarding and validation process (sanctions screening) for the Buyer moving fiat into crypto.
- **Remittance to Smart Contract:** Upon successful conversion to USDC, transfers the USDC *directly* to the FinX Escrow Smart Contract.

### 3. Off-Ramp Provider
- **Crypto-to-Fiat Conversion:** Receives USDC from the FinX Escrow Contract on behalf of the Vendor.
- **Vendor KYC/AML:** Verifies the vendor’s identity and banking details.
- **Fiat Payout:** Clears and settles INR into the vendor's local bank account.

### 4. Blockchain Infrastructure
- **Settlement Layer:** Provides the immutable ledger for Smart Contract logic. Gas limits, confirmations, and block reorganizations are handled according to Web3 consensus mechanisms.

## Compliance Requirements
- FinX must not log provider-specific PII/AML data (passports, SSNs). We only store the `providerCustomerId` and the resulting boolean `kycStatus`.
- If an entity fails Sanctions Screening via the Onramp Provider, FinX will transition the order to `FAILED` and `CANCELLED`.
