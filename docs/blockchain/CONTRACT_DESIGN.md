# Contract design v1

Immutable standalone per-deal FinXEscrow, deployed by FinXEscrowFactory, with SupportedAssetRegistry. No proxy, delegatecall, upgrade or sweep. New versions require a new factory for new deals.

| Option | Gas | Upgrade/blast radius | Complexity |
| --- | --- | --- | --- |
| Standalone (selected) | Highest per-deal deployment | Immutable, per-deal balances/storage | Simple constructor validation/verification |
| Minimal clones | Lower deployment | Isolated storage/shared implementation | Initialization and implementation verification risk |
| CREATE2 | Similar deployment plus hashing | Predictable addresses/salt policy | No product need for counterfactual funding |
| Shared ledger | Cheapest per-deal creation | One bug affects all custody | More complex isolation/indexing/upgrades |

Immutable fields: escrowId, dealIdHash, buyer, vendor, arbitrator, emergencyCouncil, registry, factory, token, totalAmount, createdAt, milestone IDs and amounts. Only hashes/random IDs; no PII/business documents. Distinct nonzero parties; arbitrator/council contract addresses distinct from parties. Code existence does not certify multisig governance. Both parties accept on-chain terms before funding. Factory enforces creator role, approved asset and unique escrow/deal hash per factory.

Registry is chain-local, asset keyed by token. Records decimals, enabled/deposit/withdraw flags and min/max base units; validates token metadata decimals. Empty by default, no FINX token. Test mocks are not deployable assets.

| Operation | Caller / checks | Result |
| --- | --- | --- |
| createEscrow | CREATOR_ROLE; valid terms; 1..64 positive unique milestones | CREATED |
| acceptAgreement | buyer/vendor, once, CREATED | Both accepted -> AWAITING_FUNDING |
| fundEscrow | buyer, AWAITING_FUNDING, enabled deposit | Atomic exact full total; FUNDED |
| submitMilestone | vendor, FUNDED allocation | SUBMITTED + timestamp; ACTIVE |
| approveMilestone | buyer, SUBMITTED | APPROVED + timestamp |
| releaseMilestone | buyer/vendor, APPROVED | Whole allocation to fixed vendor, RELEASED + timestamp |
| raiseDispute | buyer/vendor, FUNDED/SUBMITTED/APPROVED | DISPUTED; allowed during pause |
| resolveDispute | arbitrator, DISPUTED | Whole allocation to buyer OR vendor, no split |
| refundMilestone | vendor, FUNDED/SUBMITTED/APPROVED | Voluntary full buyer refund; disputes require arbitrator |
| cancelEscrow | buyer/vendor, CREATED/AWAITING_FUNDING, zero funded | CANCELLED |
| pause/unpause | immutable emergency council | Local pause; factory pause also applies |

Funding/submission/approval/release/refund/resolution require unpaused contract/factory. Money exits require withdrawal-enabled asset. Aggregate DISPUTED while any dispute remains; unaffected allocations may progress. All closed -> REFUNDED if no releases, otherwise COMPLETED including mixed outcomes. Expose totals to disambiguate. Other funded activity -> ACTIVE, initial funding -> FUNDED.

Invariants: released+refunded <= funded <= total; funded is zero or total; each allocation spent once; sum allocations = total; recognized balance = funded-released-refunded. Actual token balance >= recognized balance (unsolicited donations excluded and unrecoverable in v1). No partial funding/top-up, partial refund, split arbitration, delegated PM signature, fee deduction, permit or recipient replacement.

Use OpenZeppelin SafeERC20/ReentrancyGuard/Pausable/AccessControlDefaultAdminRules. Effects before token transfer; exact incoming/outgoing balance changes reject fee behavior, all failures revert atomically. Governance must exclude rebasing/malicious tokens. Events carry indexed escrow/milestone identifiers and fixed party/asset/amount linkage, never evidence text. Repeated settlement calls revert; future API idempotency returns existing intent. Bound gas and measure before deployment.
