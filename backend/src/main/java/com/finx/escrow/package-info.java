/**
 * Escrow Orchestration & Ledger Engine (Scheduled for Wednesday
 * Implementation).
 * <p>
 * Domain Scope:
 * <ul>
 * <li>Escrow account state machine: CREATED -> FUNDED -> LOCKED -> DISBURSED ->
 * REFUNDED -> DISPUTED.</li>
 * <li>Double-entry internal ledger tracking balance allocations per deal and
 * milestone.</li>
 * <li>Administrative escrow dispute resolution and overrides.</li>
 * </ul>
 */
package com.finx.escrow;
