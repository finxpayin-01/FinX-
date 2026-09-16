# Database change plan

Design only, no migration/live DB change in Phase 1. Use additive Flyway after V7 and actual PostgreSQL integration tests. Replace automatic history repair separately. Reuse users/deals/milestones/deliverables/disputes and historical fiat rows.

| Planned change | Constraints/purpose |
| --- | --- |
| agreement_versions + deal rail/version | Accepted immutable terms, actor/time/hash; no dual funded rail |
| blockchain_networks / supported_assets | Unique chain and (chain,address); decimals/limits/deposit/withdraw/finality configuration |
| wallets | User/org/chain/address/provider key reference; no raw keys; reviewed changes |
| blockchain_escrows / escrow_milestone_bindings | FK existing deal/milestone; unique chain/address and escrow hash; immutable allocation |
| blockchain_transactions / transaction_attempts | Unique chain/hash; sender/nonce replacement lineage; receipts and lifecycle |
| blockchain_events / chain_cursors | Unique chain/tx/log index; block/hash/canonical observations, atomic cursor advance |
| provider_transactions / provider_webhook_events | Provider/direction/reference, quote/fees/status; unique provider/event ID; encrypted envelope |
| settlements | Unique escrow/milestone/kind; separate chain/bank status and payout intent |
| ledger_accounts / journal_entries / journal_lines | Balanced asset/currency posting, unique source operation, immutable references, compensating journals |
| idempotency_records / outbox_jobs | Unique actor/operation/key, request hash/response, durable retry/lease/dead-letter |
| reconciliation_records / compliance_cases / risk_events | MATCHED/PENDING/MISMATCH/MANUAL_REVIEW, evidence/reviewer, holds/decisions |
| disputes extensions | On-chain milestone/resolution intent, distinct from case closure |

Use UUID, timestamptz, exact NUMERIC fiat and NUMERIC(78,0) base units checked between zero and 2^256-1. Enforce released+refunded <= funded <= total. Asset decimals are metadata, not assumed 18. Serialize money as strings. Never sum different currencies/assets without explicit FX valuation.

Enforce balanced posting transactionally per currency/asset, with deferred DB checks and restricted posting capability. Runtime cannot update/delete posted journals/audits. Reorg corrections reverse with linked entries. Preserve financial references against deletion. Index FKs and (created_at,id) pagination, use partial indexes for pending work and consistent lock order.

For Supabase hosting, use non-exposed finance schemas and verify actual Data API privileges. Any exposed tables need RLS ownership/tenant predicates, including USING/WITH CHECK. Current FinX JWT identity is not automatically Supabase auth.uid. No live DB/RLS configuration was verified.

Exit tests: empty PostgreSQL install, populated V7 upgrade, checksum validation, journal balance failures, concurrent idempotency/release/webhooks, tenant access, restart and backup/restore. H2 is insufficient evidence for these.
