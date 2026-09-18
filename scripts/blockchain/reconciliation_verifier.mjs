// scripts/blockchain/reconciliation_verifier.mjs
// FinX Blockchain Escrow: Automated Event Indexing & Reconciliation Engine Verification

import crypto from "crypto";

console.log("================================================================================");
console.log("  FINX BLOCKCHAIN ESCROW — EVENT INDEXER & RECONCILIATION TEST ENGINE");
console.log("================================================================================\n");

// 1. Transaction Confirmation State Machine
export const TxState = {
    CREATED: "CREATED",
    SIGNATURE_REQUESTED: "SIGNATURE_REQUESTED",
    SUBMITTED: "SUBMITTED",
    PENDING: "PENDING",
    CONFIRMED: "CONFIRMED",
    FAILED: "FAILED",
    REPLACED: "REPLACED",
    REVERTED: "REVERTED"
};

// 2. Simulated Blockchain Event Store (PostgreSQL blockchain_events replica)
class BlockchainEventIndexer {
    constructor(chainId) {
        this.chainId = chainId;
        this.processedEvents = new Set(); // Stores composite key: chainId:txHash:logIndex
        this.ledger = [];
    }

    computeEventKey(txHash, logIndex) {
        return `${this.chainId}:${txHash}:${logIndex}`;
    }

    ingestEvent(event) {
        const key = this.computeEventKey(event.transactionHash, event.logIndex);
        if (this.processedEvents.has(key)) {
            return {
                status: "DUPLICATE_IGNORED",
                key,
                reason: "Event already processed and indexed idempotently."
            };
        }

        this.processedEvents.add(key);
        this.ledger.push({
            ...event,
            indexedAt: new Date().toISOString(),
            idempotencyKey: key
        });

        return {
            status: "SUCCESS_PROCESSED",
            key
        };
    }
}

// 3. Database Projection & On-Chain State Model
class EscrowReconciliationService {
    constructor() {
        // Simulated Database State (read-model)
        this.db = {
            escrowAccounts: new Map(), // dealId -> { balance, totalFunded, totalReleased, totalRefunded, status }
            milestones: new Map() // milestoneId -> { status, amount }
        };

        // Simulated Blockchain State (authoritative source of truth)
        this.chain = {
            escrows: new Map() // escrowAddress -> { lockedAmount, fundedAmount, releasedAmount, refundedAmount, status, milestones }
        };
    }

    syncFromEvent(event) {
        let account = this.db.escrowAccounts.get(event.dealId);
        if (!account) {
            account = {
                escrowId: event.escrowId,
                dealId: event.dealId,
                balance: 0n,
                totalFunded: 0n,
                totalReleased: 0n,
                totalRefunded: 0n,
                status: "CREATED"
            };
            this.db.escrowAccounts.set(event.dealId, account);
        }

        switch (event.eventName) {
            case "EscrowCreated":
                account.status = "CREATED";
                break;
            case "EscrowFunded":
                account.totalFunded += BigInt(event.amount);
                account.balance += BigInt(event.amount);
                account.status = "FUNDED";
                break;
            case "MilestoneReleased":
                account.totalReleased += BigInt(event.amount);
                account.balance -= BigInt(event.amount);
                account.status = account.balance === 0n ? "COMPLETED" : "ACTIVE";
                break;
            case "RefundIssued":
                account.totalRefunded += BigInt(event.amount);
                account.balance -= BigInt(event.amount);
                account.status = account.balance === 0n ? (account.totalReleased === 0n ? "REFUNDED" : "COMPLETED") : "ACTIVE";
                break;
            case "DisputeRaised":
                account.status = "DISPUTED";
                break;
            case "EscrowCancelled":
                account.status = "CANCELLED";
                break;
        }
    }

    reconcile(dealId, escrowAddress) {
        const dbRecord = this.db.escrowAccounts.get(dealId);
        const chainRecord = this.chain.escrows.get(escrowAddress);

        if (!dbRecord || !chainRecord) {
            return {
                reconciled: false,
                error: "Entity missing from DB or Chain",
                dbFound: !!dbRecord,
                chainFound: !!chainRecord
            };
        }

        const discrepancies = [];

        if (dbRecord.balance !== chainRecord.lockedAmount) {
            discrepancies.push({
                field: "balance vs lockedAmount",
                db: dbRecord.balance.toString(),
                chain: chainRecord.lockedAmount.toString()
            });
        }

        if (dbRecord.totalFunded !== chainRecord.fundedAmount) {
            discrepancies.push({
                field: "totalFunded vs fundedAmount",
                db: dbRecord.totalFunded.toString(),
                chain: chainRecord.fundedAmount.toString()
            });
        }

        if (dbRecord.totalReleased !== chainRecord.releasedAmount) {
            discrepancies.push({
                field: "totalReleased vs releasedAmount",
                db: dbRecord.totalReleased.toString(),
                chain: chainRecord.releasedAmount.toString()
            });
        }

        if (dbRecord.totalRefunded !== chainRecord.refundedAmount) {
            discrepancies.push({
                field: "totalRefunded vs refundedAmount",
                db: dbRecord.totalRefunded.toString(),
                chain: chainRecord.refundedAmount.toString()
            });
        }

        if (dbRecord.status !== chainRecord.status) {
            discrepancies.push({
                field: "status",
                db: dbRecord.status,
                chain: chainRecord.status
            });
        }

        // Mathematical conservation check on both layers
        const dbConservation = dbRecord.totalFunded === (dbRecord.totalReleased + dbRecord.totalRefunded + dbRecord.balance);
        const chainConservation = chainRecord.fundedAmount === (chainRecord.releasedAmount + chainRecord.refundedAmount + chainRecord.lockedAmount);

        return {
            reconciled: discrepancies.length === 0,
            dealId,
            escrowAddress,
            discrepancies,
            dbConservation,
            chainConservation
        };
    }
}

// 4. EXECUTION TESTS

async function runReconciliationTests() {
    let passedTests = 0;
    let totalTests = 0;

    function assert(condition, message) {
        totalTests++;
        if (!condition) {
            console.error(`  ❌ FAIL: ${message}`);
            throw new Error(`Assertion failed: ${message}`);
        } else {
            console.log(`  ✔ PASS: ${message}`);
            passedTests++;
        }
    }

    console.log("Test Suite 1: Event Indexer Idempotency & Deduplication");
    const indexer = new BlockchainEventIndexer(137); // Polygon Mainnet / Amoy
    const sampleTxHash = "0x" + crypto.randomBytes(32).toString("hex");

    const event1 = {
        transactionHash: sampleTxHash,
        logIndex: 0,
        eventName: "EscrowCreated",
        escrowId: "0x1111",
        dealId: "deal-abc",
        blockNumber: 10001
    };

    const res1 = indexer.ingestEvent(event1);
    assert(res1.status === "SUCCESS_PROCESSED", "First event ingestion succeeded");

    // Attempting duplicate event ingestion
    const res2 = indexer.ingestEvent(event1);
    assert(res2.status === "DUPLICATE_IGNORED", "Duplicate event correctly ignored by composite key");
    assert(indexer.ledger.length === 1, "Ledger contains exactly 1 unique event");

    console.log("\nTest Suite 2: Multi-Milestone Financial Reconciliation (Happy Path)");
    const service = new EscrowReconciliationService();
    const dealId = "deal-10k-units";
    const escrowAddr = "0x" + crypto.randomBytes(20).toString("hex");

    // Initialize chain state
    service.chain.escrows.set(escrowAddr, {
        lockedAmount: 10000000000n,
        fundedAmount: 10000000000n,
        releasedAmount: 0n,
        refundedAmount: 0n,
        status: "FUNDED"
    });

    // Ingest on-chain events into DB projection
    service.syncFromEvent({
        eventName: "EscrowCreated",
        dealId,
        escrowId: "0x" + crypto.randomBytes(32).toString("hex"),
        transactionHash: sampleTxHash,
        logIndex: 1
    });
    service.syncFromEvent({
        eventName: "EscrowFunded",
        dealId,
        amount: "10000000000",
        transactionHash: sampleTxHash,
        logIndex: 2
    });

    let rec = service.reconcile(dealId, escrowAddr);
    assert(rec.reconciled === true, "Initial funded state matches perfectly across DB and Blockchain");
    assert(rec.dbConservation === true && rec.chainConservation === true, "Conservation holds on both layers");

    // Settle Milestone 1 (2,000 units)
    service.chain.escrows.get(escrowAddr).lockedAmount -= 2000000000n;
    service.chain.escrows.get(escrowAddr).releasedAmount += 2000000000n;
    service.chain.escrows.get(escrowAddr).status = "ACTIVE";

    service.syncFromEvent({
        eventName: "MilestoneReleased",
        dealId,
        amount: "2000000000",
        transactionHash: "0x" + crypto.randomBytes(32).toString("hex"),
        logIndex: 0
    });

    rec = service.reconcile(dealId, escrowAddr);
    assert(rec.reconciled === true, "After M1 release, DB balance (8,000) matches Chain lockedAmount (8,000)");

    // Settle Milestone 2 (3,000 units)
    service.chain.escrows.get(escrowAddr).lockedAmount -= 3000000000n;
    service.chain.escrows.get(escrowAddr).releasedAmount += 3000000000n;

    service.syncFromEvent({
        eventName: "MilestoneReleased",
        dealId,
        amount: "3000000000",
        transactionHash: "0x" + crypto.randomBytes(32).toString("hex"),
        logIndex: 0
    });

    rec = service.reconcile(dealId, escrowAddr);
    assert(rec.reconciled === true, "After M2 release, DB balance (5,000) matches Chain lockedAmount (5,000)");

    // Settle Milestone 3 (5,000 units - Full Completion)
    service.chain.escrows.get(escrowAddr).lockedAmount -= 5000000000n;
    service.chain.escrows.get(escrowAddr).releasedAmount += 5000000000n;
    service.chain.escrows.get(escrowAddr).status = "COMPLETED";

    service.syncFromEvent({
        eventName: "MilestoneReleased",
        dealId,
        amount: "5000000000",
        transactionHash: "0x" + crypto.randomBytes(32).toString("hex"),
        logIndex: 0
    });

    rec = service.reconcile(dealId, escrowAddr);
    assert(rec.reconciled === true, "Terminal completion: DB status == COMPLETED, balance == 0, Chain locked == 0");
    assert(service.db.escrowAccounts.get(dealId).totalReleased === 10000000000n, "Total 10,000 units released");

    console.log("\nTest Suite 3: Divergence & Tampering Detection");
    // Simulate deliberate tampering in DB
    service.db.escrowAccounts.get(dealId).balance = 500n; // Artificial phantom balance
    rec = service.reconcile(dealId, escrowAddr);
    assert(rec.reconciled === false, "Divergence engine successfully catches phantom balance tampering");
    assert(rec.discrepancies.length > 0, "Discrepancy report populated with details");
    assert(rec.dbConservation === false, "DB conservation failure correctly detected");

    console.log(`\n================================================================================`);
    console.log(`  RECONCILIATION & INDEXING VALIDATION COMPLETE: ${passedTests}/${totalTests} TESTS PASSED`);
    console.log(`================================================================================`);
}

runReconciliationTests().catch(err => {
    console.error("Reconciliation test suite failed:", err);
    process.exit(1);
});
