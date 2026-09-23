# FINX — Legacy Migration & Quarantine Report

## 1. Migration Overview
FinX has successfully pivoted from its previous hybrid Web3/blockchain and Razorpay payment architecture into a centralized, bank-integrated B2B milestone-payment and trust infrastructure platform.

---

## 2. Legacy Components Quarantined & Removed

### A. Blockchain & Web3 Infrastructure
- **Solidity Smart Contracts & Foundry**: Moved from root and quarantined in `legacy/blockchain/contracts/` and `legacy/blockchain/foundry/`.
- **Ethers.js / Web3j Dependencies**: Removed `org.web3j:core` from `backend/pom.xml`. Upgraded backend toolchain to Java 21 LTS with Spring Boot 3.4.3.
- **Backend Blockchain Controllers & Services**:
  - `BlockchainEscrowController.java`
  - `BlockchainEscrowService.java`
  - `BlockchainTransactionManager.java`
  - `WalletVerificationService.java`
  - Quarantined into `legacy/backend-blockchain/`.
- **Onramper Fiat-to-Crypto Integration**:
  - `OnrampController.java`, `OnrampService.java`, `OnrampTransactionRepository.java` moved to `legacy/backend-blockchain/`.
- **Removed Environment Variables**:
  - `PRIVATE_KEY`, `RPC_URL`, `POLYGON_RPC_URL`, `AMOY_RPC_URL`, `CONTRACT_ADDRESS`, `FACTORY_CONTRACT_ADDRESS`, `TOKEN_CONTRACT_ADDRESS`, `WALLET_PRIVATE_KEY`.

### B. Razorpay Legacy Code
- **Razorpay Rails**: Discontinued active use of `RAZORPAY` payment rail. The core payment engine now routes strictly through `PaymentRail.BANK`.
- **Frontend Cleanup**:
  - Removed `NEXT_PUBLIC_RAZORPAY_KEY_ID` from `.env.example`.
  - Replaced legacy Razorpay UI elements with standardized Bank Payment Instruction modals and Virtual Account Number (VAN) deposit workflows.

---

## 3. Database Schema Evolution (Flyway V11–V15)
To preserve historical deal and user data without breaking existing relational integrity, all legacy tables (e.g., `deals`, `milestones`, `deliverables`, `disputes`) were retained and extended through additive Flyway migrations:
- `V11__b2b_organization_kyc_kyb.sql`: Multi-tenant `organizations`, `organization_members`, `organization_bank_accounts`, `kyc_profiles`, `kyb_profiles`.
- `V12__double_entry_ledger.sql`: `ledger_accounts`, `journal_entries`, `journal_lines`.
- `V13__bank_payment_orchestration_and_settlement.sql`: `payment_instructions`, `payment_transactions`, `settlement_instructions`, `refunds`.
- `V14__reconciliation_and_risk.sql`: `reconciliation_runs`, `reconciliation_items`, `risk_assessments`, `risk_signals`.
- `V15__deal_agreements_and_b2b_updates.sql`: `deal_agreements`, `agreement_audit_events`, B2B columns on `deals` (`deal_reference`, `buyer_organization_id`, `seller_organization_id`).

All 15 Flyway migrations pass clean migration validation tests with 0 checksum drift.
