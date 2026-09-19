# Blockchain escrow flow

1. Buyer and vendor register/login using existing FinX authentication.
2. Buyer creates a deal; assigned vendor accepts; buyer creates milestones.
3. Both parties verify their browser wallet with a server nonce signature.
4. Buyer enters independent token amounts for every milestone and creates escrow terms. Allocation schedule freezes.
5. Authorized admin connects the configured factory creator wallet and deploys the existing FinXEscrow contract. Deployment stays pending until a canonical confirmed receipt is indexed.
6. Buyer and vendor each accept the immutable terms in their own wallets.
7. Funding intent points to the buyer wallet. Local tests mint TEST tokens; Onramper sandbox only exercises provider UI. A direct transfer to the escrow address does not fund it and may strand tokens.
8. Buyer authorizes the exact ERC20 amount and calls fundEscrow. FinX shows submitted/pending until confirmed state and events establish full funding.
9. Vendor starts work and submits existing deliverables, then submits the milestone on-chain. PM/buyer reviews work in FinX; buyer separately signs on-chain approval.
10. Party requests release. Backend checks membership, application approval, dispute hold and indexed chain approval. Smart contract enforces authorization, state and immutable recipient independently.
11. Confirmed event and state projection update released amounts and milestone COMPLETED. Vendor receives tokens, not INR bank settlement.

## State transitions

Contract: CREATED -> AWAITING_FUNDING (both accept) -> FUNDED -> ACTIVE -> COMPLETED. Unfunded cancellation -> CANCELLED. Entirely refunded escrow -> REFUNDED. Any unsettled allocation can enter DISPUTED; authorized resolution settles that allocation to fixed buyer or vendor. Any active dispute blocks normal releases across the escrow.

Allocation: PENDING -> FUNDED -> SUBMITTED -> APPROVED -> RELEASED. FUNDED/SUBMITTED/APPROVED can become DISPUTED or be voluntarily REFUNDED by vendor. DISPUTED resolves to RELEASED/REFUNDED. Terminal allocations cannot settle again.

Application transaction: CREATED (wallet signature required) -> SUBMITTED -> PENDING -> CONFIRMED or REVERTED. Incorrect transaction contents -> FAILED. Verified same-nonce replacement preserves old record as REPLACED. Reorg -> PENDING_RECONCILIATION plus escrow REORG_REVIEW. There is no database-only successful blockchain settlement.

## Disputes

Existing FinX dispute cases create an application hold immediately. Parties must also submit a chain dispute to enforce the hold against direct wallet calls. A release already ordered before a dispute on-chain cannot be recalled. These are different states, not an atomic cross-system lock.

Admin proposes RESOLVE_BUYER or RESOLVE_VENDOR with a reason. UI exposes fixed target/call data for the configured council's multisig workflow. FinX does not provide arbitrary recipient transfers or execute council governance. Operator attaches the council execution hash; the indexer verifies its outcome. Close the FinX case only after its allocations have confirmed terminal settlement. Each proposal and transaction has actor, role, reason, project/milestone references and audit evidence.

## Recovery

- Rejected wallet signature: retry the same prepared intent; no funds moved.
- Browser closes after broadcast: recover hash from wallet and attach it to the persisted intent. Hash is cached locally after wallet response.
- Replacement: submit replacement hash; backend checks same sender/nonce/destination/calldata. If the node no longer knows the original, leave pending for reconciliation.
- Confirmed revert: use a fresh HTTP idempotency key to create an additional attempt, retaining the previous record.
- RPC/database outage: do not infer success. Indexer resumes durable ranges after recovery.
- Reorg below checkpoint: hold the escrow for review; do not edit balances or migration history to force a match.
