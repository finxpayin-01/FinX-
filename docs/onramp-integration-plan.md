# FINX: On-Ramp Integration Plan

## Current Architecture
- **Backend:** Spring Boot (Java) with PostgreSQL (via Flyway), Redis, JWT auth, and RBAC. Domain models represent Users, Deals, Milestones, and Payments.
- **Frontend:** Next.js.
- **Payment Abstraction:** The current `Payment` entity focuses primarily on Razorpay (with `providerOrderId`, `providerPaymentId`, `providerSignature`).
- **Escrow:** Existing `deals`, `escrow_accounts`, `escrow_ledger`, and `disputes` tables exist. The smart contract component is outlined in `TARGET_ARCHITECTURE.md`.
- **Infrastructure:** Configured with `.env`, Render deployments, Supabase.

## Gaps
1. **Lack of Generic Funding Rail:** Hardcoded to `RAZORPAY` in many places or lacking a proper interface to select rails (Blockchain On-Ramp vs Razorpay).
2. **Missing On-Ramp Domain Model:** No `OnRampTransaction` table or related state tracking in Postgres to handle the complex states of fiat-to-crypto bridging.
3. **No Webhook Signature Verification / Idempotency:** Missing robust, abstracted Webhook verification and transaction reconciliation for On-Ramp.
4. **Missing Blockchain Settlement Verifier:** FinX cannot just trust the "Quote Completed" webhook; it needs to verify against the EVM RPC for finality.
5. **UI Support:** The frontend doesn't have an On-Ramp funding flow, it just uses standard checkout/milestone views.

## Proposed Architecture
We will implement an **OnRampProvider** abstraction, separating Fiat processing from Crypto escrow. The flow is split into two systems: 
1. `FundingRail` - Enum routing to either ONRAMP or RAZORPAY.
2. `Payment` records vs `OnRampTransaction` state machines.

### 1. Database Changes
- **Flyway V8 (`V8__create_onramp_transactions.sql`)**: Create `onramp_transactions` table exactly as requested.
- Create `offramp_transactions` and `webhook_events` tracking tables.
- Add `FundingRail` enum or column to milestones/payments so rails don't magically switch.

### 2. API Changes
- `POST /api/onramp/quote`: Fetch quotes from `OnRampProvider`.
- `POST /api/onramp/session`: Create checkout session for the onramp widget/redirect.
- `POST /api/webhooks/onramp`: Secure ingest of provider events -> updates `OnRampTransaction`.
- Background worker / Event Listener: A `BlockchainSettlementVerifier` checks EVM node after webhook success before marking `ESCROW_FUNDED`.

### 3. Blockchain Changes
- Configure target EVM node settings in `.env`.
- Build `BlockchainSettlementVerifier` to confirm the on-ramp converted USDC has hit the `FINX_ESCROW_CONTRACT_ADDRESS`.

### 4. Frontend Changes
- Update Milestone "Fund" view to show `BLOCKCHAIN_ONRAMP` vs `RAZORPAY`.
- Render OnRamp iframe/redirect. 
- Display strict states based on backend polling (e.g. `AWAITING_PAYMENT`, `CRYPTO_SENT`, `BLOCKCHAIN_CONFIRMING`).

### 5. Security Changes
- Webhook signature HMAC verification service.
- Immutable Audit logging framework for events (`WebhookEvent`).
- Idempotency implementation (via Idempotency-Key HTTP headers & Redis or DB table filtering).

### 6. Deployment Changes
- Extend `render.yaml` with required placeholder variables.
- Update `.env.example` to define:
  ```
  ONRAMP_PROVIDER=
  ONRAMP_ENVIRONMENT=sandbox
  ONRAMP_API_KEY=
  ...
  ```

## Migration Strategy
- Continue accepting Razorpay on old transactions. 
- Ensure `V8` applies forward-only. No mutating existing Razorpay payments.
- Deploy the webhooks inertly, test them in Sandbox, then enable the UI.
