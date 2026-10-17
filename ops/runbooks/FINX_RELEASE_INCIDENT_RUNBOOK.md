# FinX Release Execution Incident Response & Operational Runbook

**Document Version:** 1.0.0 (E12 Production Certification)  
**Classification:** Confidential — Internal Engineering & Finance Operations  
**Scope:** Post-Engine Release Execution Platform, Financial Outbox, Banking Provider Gateway, Trusted Settlement Finalizer, and Deterministic Reconciliation.

---

## 1. Severity Classifications & SLAs

| Severity | Definition | Incident Response SLA | Update Cadence | Escalation Contacts |
| :--- | :--- | :--- | :--- | :--- |
| **SEV-1 (Critical)** | Potential financial loss, reconciliation mismatch, unexpected production gate opening, suspected double-spend, or bank dispatch state divergence. | **Immediate (< 15 mins)** | Every 30 minutes | VP Engineering, Head of Finance, On-call Lead, Security Incident Lead |
| **SEV-2 (Major)** | Outbox worker pipeline stalled, dead-letter queue accumulation, high bank failure rate (> 5%), or observation polling lag > 30 minutes. | **< 30 mins** | Every 60 minutes | Tech Lead, Settlement Operations Lead |
| **SEV-3 (Moderate)** | Delayed bank statement ingestion, isolated transient timeouts with successful retry, single message admin hold review. | **< 2 hours** | Daily / As Resolved | On-call Engineer |

---

## 2. Emergency Controls & Kill Switches

### 2.1 The Master Production Kill Switch
If any unapproved external financial transfer occurs, or if anomalous partner bank responses are observed:

1. **Immediate Config Deactivation**:
   Set `finx.release.execution.enabled=false` in the application runtime environment and restart or apply dynamic refresh:
   ```bash
   # Kubernetes / Container environment variable override
   kubectl set env deployment/finx-backend FINX_RELEASE_EXECUTION_ENABLED=false
   ```
2. **Impact**:
   - `ProductionSettlementDispatchGate` immediately fails-closed (`isProductionDispatchAllowed() -> false`).
   - 100% of outbound banking provider dispatch calls are rejected with `IllegalStateException("Production settlement dispatch gate is CLOSED")`.
   - Internal ledger reservations, engine authorizations, and outbox queue entries are safely preserved without data loss.

### 2.2 Placing an Individual Release on Administrative Hold
To isolate a suspicious, disputed, or failing release instruction without stopping the entire platform:

- **Via REST API (Admin Authentication Required)**:
  ```http
  PUT /api/v1/finance/release-operations/hold/{releaseInstructionId}?reason=Suspicious+beneficiary+activity+under+review
  Authorization: Bearer <ADMIN_JWT>
  ```
- **Via Direct Database Operational Control (Emergency Fallback)**:
  ```sql
  UPDATE financial_outbox
  SET is_admin_hold = TRUE,
      admin_hold_reason = 'SEV-1 Emergency Operator Freeze',
      admin_hold_by = 'OPS-EMERGENCY',
      admin_hold_at = NOW(),
      updated_at = NOW()
  WHERE release_instruction_id = '<RELEASE_INSTRUCTION_ID>'
    AND status IN ('PENDING', 'PROCESSING');
  ```
- **Verification**:
  Confirm message is held:
  ```sql
  SELECT id, release_instruction_id, status, is_admin_hold, admin_hold_reason
  FROM financial_outbox
  WHERE release_instruction_id = '<RELEASE_INSTRUCTION_ID>';
  ```

---

## 3. Alert Triage & Root Cause Investigation

### Alert 1: `FinxProductionGateUnexpectedlyOpen` (SEV-1)
- **Description**: Production gate metric `finx.release.execution.gate.status == 1` fired before authorized go-live certification.
- **Immediate Action**:
  1. Trigger Kill Switch immediately.
  2. Inspect pod environment variables for unauthorized `FINX_RELEASE_EXECUTION_ENABLED=true`.
  3. Query audit log:
     ```sql
     SELECT * FROM audit_logs
     WHERE action = 'PRODUCTION_GATE_CONFIG_MODIFIED'
     ORDER BY created_at DESC LIMIT 10;
     ```

### Alert 2: `FinxReconciliationMismatchDetected` (SEV-1)
- **Description**: Reconciler found unmatched debits or amount divergences (`reconciliation_items.status = 'MISMATCH'`).
- **Immediate Action**:
  1. Halt statement ingestion and place corresponding release on administrative hold.
  2. Inspect mismatch details:
     ```sql
     SELECT ri.id, ri.run_id, ri.internal_effect_id, ri.reason_code, ri.narrative,
            sl.amount AS statement_amount, sl.utr_number AS statement_utr,
            rinst.amount AS instruction_amount, rinst.currency
     FROM reconciliation_items ri
     JOIN bank_statement_lines sl ON ri.statement_line_id = sl.id
     LEFT JOIN release_instructions rinst ON ri.internal_effect_id = rinst.id
     WHERE ri.status = 'MISMATCH'
     ORDER BY ri.created_at DESC;
     ```
  3. Cross-reference partner bank physical statement or portal.

### Alert 3: `FinxOutboxDeadLetterSpike` (SEV-2)
- **Description**: Messages exceeded 3 retry attempts and moved to `DEAD_LETTER`.
- **Immediate Action**:
  1. Inspect dead-letter messages and error codes:
     ```sql
     SELECT id, release_instruction_id, attempt_count, last_error_code, updated_at
     FROM financial_outbox
     WHERE status = 'DEAD_LETTER'
     ORDER BY updated_at DESC LIMIT 20;
     ```
  2. Common causes: Partner gateway connection timeouts (HTTP 504), Invalid beneficiary IFSC, or Account frozen at bank.

### Alert 4: `FinxOutboxStaleLeaseBacklog` (SEV-2)
- **Description**: Outbox messages locked by worker threads exceeding 300 seconds without completion.
- **Immediate Action**:
  1. Check worker container health / OOM kills:
     ```bash
     kubectl logs -l app=finx-backend --tail=500 | grep -i "outbox"
     ```
  2. Verify if automatic recovery worker re-leased stale records:
     ```sql
     SELECT id, release_instruction_id, locked_by, locked_at, status
     FROM financial_outbox
     WHERE status = 'PROCESSING' AND locked_at < NOW() - INTERVAL '5 minutes';
     ```

---

## 4. Resolution & Recovery Workflows

### 4.1 Releasing Administrative Hold
Once an investigation concludes that an instruction is safe to process:

- **Via REST API**:
  ```http
  PUT /api/v1/finance/release-operations/release-hold/{releaseInstructionId}
  Authorization: Bearer <ADMIN_JWT>
  ```
- **Via Database**:
  ```sql
  UPDATE financial_outbox
  SET is_admin_hold = FALSE,
      admin_hold_reason = NULL,
      admin_hold_by = NULL,
      admin_hold_at = NULL,
      status = 'PENDING',
      available_at = NOW(),
      updated_at = NOW()
  WHERE release_instruction_id = '<RELEASE_INSTRUCTION_ID>'
    AND is_admin_hold = TRUE;
  ```

### 4.2 Handling Stale / Zombie Execution Attempts
If a worker crashed while awaiting an HTTP response from the bank:
1. DO NOT immediately re-dispatch without confirming status at the bank!
2. Inspect the execution attempt:
   ```sql
   SELECT id, release_instruction_id, provider_operation_key, provider_settlement_id, status, error_message
   FROM settlement_execution_attempts
   WHERE release_instruction_id = '<RELEASE_INSTRUCTION_ID>'
   ORDER BY attempt_number DESC;
   ```
3. Use the `provider_operation_key` (`FINX-REL-<instruction_id>`) to query the bank's idempotency endpoint / portal.
4. If bank confirms payment was executed:
   - Manually insert/trigger `SettlementObservation` with `FINAL_SUCCESS` and UTR.
   - Trigger `TrustedSettlementFinalizer.finalizeRelease(instructionId)`.
5. If bank confirms payment was never received:
   - Mark attempt as `FAILED` and reset outbox message to `PENDING`.

### 4.3 Manual Double-Entry Journal Adjustment
If financial discrepancy requires ledger correction:
- NEVER perform raw SQL updates on `ledger_journal_entries` or `escrow_accounts` balance columns!
- Post an offsetting balancing entry through `LedgerService.postJournalEntry()` using appropriate correction transaction type (`ADJUSTMENT` or `REVERSAL`).
- Both debit and credit lines must sum exactly to 0.

---

## 5. Post-Incident Review (PIR) Protocol
- Every SEV-1 or SEV-2 incident requires a formal Post-Incident Review within 48 hours.
- **Required Artifacts**:
  1. Incident Timeline (UTC and IST timestamps of detection, triage, mitigation, resolution).
  2. Impact Assessment (Number of deals/milestones affected, financial value, duration of gate closure).
  3. Root Cause Analysis (5 Whys methodology).
  4. Corrective & Preventative Actions (CAPA) with assigned owners and Jira ticket tracking.
