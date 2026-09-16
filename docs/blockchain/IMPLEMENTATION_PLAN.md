# Implementation plan

First checkpoint: Phase 0 documents then Phase 1 contracts, with initial executable unit/fuzz/invariant validation. No application activation until dependent phases pass.

| Phase | Deliverable | Exit evidence/blockers |
| --- | --- | --- |
| 0 | Eight audit/design documents | Source evidence, component disposition, baseline tests |
| 1 | Immutable escrow/factory/empty asset registry | Compile, money/authorization/state tests, documented limits, commit |
| 2 | Expanded fuzz/invariant/adversarial/gas/static review | Repeatable CI, failure corpus, independent audit plan |
| 3 | Testnet deployment/verification | Actual network/token/RPC, funded signer, reviewed governance and receipt manifest |
| 4 | Spring gateway/signers/durable intents | Timeout/nonce/replacement tests, fail-closed production signer |
| 5 | Polling indexer/optional WS | Duplicate/reorder/restart/reorg/finality tests |
| 6 | Additive Flyway/balanced ledger | PostgreSQL migrations/concurrency/append-only checks |
| 7 | On-ramp adapters | Signed webhook, quote/mismatch/outage recovery; approved provider |
| 8 | Off-ramp/bank adapters | Separate settlement, reversal/retry/no duplicate payout |
| 9 | Frontend/admin integration | Decimal strings, finality/settlement UX, role E2E |
| 10 | Reconciliation | Orphan/missing/duplicate/stuck detection, reviewed recovery |
| 11 | Compliance/risk/legal dossier | Verified decisions/holds/limits/four-eyes; legal review |
| 12 | Hardening/incident response | Static/dependency/history-secret scans, tenant fixes, custody review |
| 13 | Full sandbox E2E/recovery/load | Actual testnet/provider trace, balanced ledger, defined SLOs |
| 14 | Deployment/readiness | All production gates evidenced; separately controlled mainnet |

Every phase: inspect -> implement -> test -> fix -> verify -> document -> commit. Initial Phase 1 tests do not complete Phase 2 assurance. Never treat an interface or mock as real integration.

Config remains empty until approved: chain ID, HTTP/WS RPC, finality count, factory/token/decimals/limits, signer provider/key reference, on/off-ramp provider/API keys/webhook secrets, Redis/database URLs. No provider/signer secrets in NEXT_PUBLIC variables.

PRODUCTION READINESS: NOT YET APPROVED. Gates: contract tests; fuzz/invariants; no known critical/high issues after review; independent audit; custody review; production ramps; compliance implementation; Indian legal review; reconciliation; disaster recovery; load SLOs; monitoring/incident response; history secret scan; verified mainnet contracts/reviewed signed manifest. No gate is satisfied merely by writing this plan.
