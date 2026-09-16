# Blockchain migration plan

Checkpoint: Phase 0 and Phase 1, with executable contract validation. No live deployment or application activation.

1. Add isolated contracts; freeze ABI/state model after review.
2. Complete adversarial/fuzz/invariant/static review, then configure actual testnet/approved test asset and receipt-backed manifest. Never invent addresses.
3. Add disabled-by-default Java blockchain domain alongside fiat. Persist FIAT_RAZORPAY or BLOCKCHAIN at immutable accepted agreement version. No dual-funded deal or rail switch after funding without reviewed unwind.
4. Add PostgreSQL intents/outbox/inbox, scoped idempotency and gateway/indexer. HTTP returns intent state; only verified canonical finalized events update chain custody.
5. Add balanced journals; preserve historical escrow_ledger. Opening entries require verified statements and sign-off, never synthetic blockchain funding from fiat balances.
6. Add approved signer/ramp/compliance adapters; fail closed when absent. Explicit local mocks cannot load in production.
7. Integrate frontend/admin read models after authoritative APIs. Default new eligible deals to blockchain only after sandbox recovery/E2E passes.

Rollback disables new intents/providers, but leaves indexer and reconciliation running. Funded contracts cannot be undone by application rollback. Preserve events and compensating journals, freeze mismatches, recover using authorized contract operations. Never automatically retry an uncertain blockchain broadcast over Razorpay.

Each phase requires tests, documented failures/blockers and a commit. Missing external configuration blocks deployment, not local contract development.
