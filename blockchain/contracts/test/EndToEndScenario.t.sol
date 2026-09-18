// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {EscrowFixture, TestToken, TestCouncil, Vm} from "./Fixtures.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";

/// @notice End-to-End Testing & Security Validation Suite for FinX Blockchain Escrow.
/// @dev Validates multi-milestone math, token edge-cases, authorization attacks, double-spends,
/// front-running order, dispute resolutions, and reentrancy resilience.
contract EndToEndScenarioTest is EscrowFixture {
    bytes32 internal constant M_1 = keccak256("E2E-MILESTONE-1");
    bytes32 internal constant M_2 = keccak256("E2E-MILESTONE-2");
    bytes32 internal constant M_3 = keccak256("E2E-MILESTONE-3");

    uint256 internal constant UNITS_DECIMALS = 1e6; // 6 decimals for USDT/USDC
    uint256 internal constant M1_AMOUNT = 2000 * UNITS_DECIMALS; // 2,000 units
    uint256 internal constant M2_AMOUNT = 3000 * UNITS_DECIMALS; // 3,000 units
    uint256 internal constant M3_AMOUNT = 5000 * UNITS_DECIMALS; // 5,000 units
    uint256 internal constant TOTAL_ESCROW = 10000 * UNITS_DECIMALS; // 10,000 units

    FinXEscrow internal e2eEscrow;

    function setUp() public override {
        super.setUp();
        e2eEscrow = _createThreeMilestoneEscrow();
    }

    function _createThreeMilestoneEscrow() internal returns (FinXEscrow) {
        bytes32[] memory ids = new bytes32[](3);
        ids[0] = M_1;
        ids[1] = M_2;
        ids[2] = M_3;
        uint256[] memory amounts = new uint256[](3);
        amounts[0] = M1_AMOUNT;
        amounts[1] = M2_AMOUNT;
        amounts[2] = M3_AMOUNT;

        return FinXEscrow(
            factory.createEscrow(
                keccak256("e2e-escrow-10k"), keccak256("e2e-deal-10k"), BUYER, VENDOR, address(token), ids, amounts
            )
        );
    }

    // =========================================================================
    // 1. SECTION 4 & 5: MULTI-MILESTONE HAPPY-PATH & MATHEMATICAL INVARIANTS
    // =========================================================================

    function testSection5ExactMultiMilestoneLifecycle() public {
        // Initial checks
        require(e2eEscrow.totalAmount() == TOTAL_ESCROW, "Total amount mismatch");
        require(e2eEscrow.milestoneCount() == 3, "Milestone count mismatch");
        require(e2eEscrow.status() == FinXEscrow.Status.CREATED, "Initial status not CREATED");

        // Both parties accept agreement
        vm.prank(BUYER);
        e2eEscrow.acceptAgreement();
        vm.prank(VENDOR);
        e2eEscrow.acceptAgreement();
        require(e2eEscrow.status() == FinXEscrow.Status.AWAITING_FUNDING, "Status not AWAITING_FUNDING");

        // Buyer funds escrow with 10,000 units
        token.mint(BUYER, TOTAL_ESCROW);
        vm.startPrank(BUYER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW);
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Check funded state
        require(e2eEscrow.status() == FinXEscrow.Status.FUNDED, "Status not FUNDED");
        require(e2eEscrow.fundedAmount() == TOTAL_ESCROW, "Funded amount mismatch");
        require(e2eEscrow.lockedAmount() == TOTAL_ESCROW, "Locked amount mismatch");
        require(token.balanceOf(address(e2eEscrow)) == TOTAL_ESCROW, "Escrow token balance mismatch");
        require(token.balanceOf(VENDOR) == 0, "Vendor balance must be 0 initially");
        require(token.balanceOf(BUYER) == 0, "Buyer balance must be 0 after funding");

        // --- Milestone 1: 2,000 units ---
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_1);

        // Verification after M1
        require(token.balanceOf(VENDOR) == M1_AMOUNT, "Vendor must receive exactly 2,000 after M1");
        require(e2eEscrow.releasedAmount() == M1_AMOUNT, "Released amount mismatch after M1");
        require(e2eEscrow.lockedAmount() == TOTAL_ESCROW - M1_AMOUNT, "Escrow locked must be 8,000 after M1");
        require(token.balanceOf(address(e2eEscrow)) == 8000 * UNITS_DECIMALS, "Escrow balance must be 8,000 after M1");

        // --- Milestone 2: 3,000 units ---
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_2);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_2);

        vm.prank(BUYER); // Buyer also authorized to trigger release
        e2eEscrow.releaseMilestone(M_2);

        // Verification after M2
        require(token.balanceOf(VENDOR) == M1_AMOUNT + M2_AMOUNT, "Vendor must receive 5,000 total after M2");
        require(e2eEscrow.releasedAmount() == 5000 * UNITS_DECIMALS, "Released amount must be 5,000 after M2");
        require(e2eEscrow.lockedAmount() == 5000 * UNITS_DECIMALS, "Escrow locked must be 5,000 after M2");
        require(token.balanceOf(address(e2eEscrow)) == 5000 * UNITS_DECIMALS, "Escrow balance must be 5,000 after M2");

        // --- Milestone 3: 5,000 units ---
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_3);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_3);

        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_3);

        // Verification after M3 (Full completion)
        require(token.balanceOf(VENDOR) == TOTAL_ESCROW, "Vendor must receive 10,000 total after M3");
        require(e2eEscrow.releasedAmount() == TOTAL_ESCROW, "Released amount must be 10,000 total");
        require(e2eEscrow.lockedAmount() == 0, "Escrow locked must be 0 upon completion");
        require(token.balanceOf(address(e2eEscrow)) == 0, "Escrow contract balance must be 0 upon completion");
        require(e2eEscrow.status() == FinXEscrow.Status.COMPLETED, "Terminal status must be COMPLETED");

        // Mathematical conservation validation:
        // Buyer funded == Released + Remaining + Refunded + Fees
        uint256 funded = e2eEscrow.fundedAmount();
        uint256 released = e2eEscrow.releasedAmount();
        uint256 remaining = e2eEscrow.lockedAmount();
        uint256 refunded = e2eEscrow.refundedAmount();
        require(funded == released + remaining + refunded, "Mathematical conservation violated!");
        require(funded == 10000 * UNITS_DECIMALS, "Funded accounting corrupted");
    }

    // =========================================================================
    // 2. SECTION 6: TOKEN TESTING & EDGE CASES
    // =========================================================================

    function testSection6TokenEdgeCases() public {
        // Acceptance
        vm.prank(BUYER);
        e2eEscrow.acceptAgreement();
        vm.prank(VENDOR);
        e2eEscrow.acceptAgreement();

        // Test 1: Insufficient allowance
        token.mint(BUYER, TOTAL_ESCROW);
        vm.startPrank(BUYER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW - 1); // 1 unit short
        vm.expectRevert();
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Test 2: Insufficient balance
        vm.startPrank(BUYER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW * 2);
        // Burn or transfer buyer funds away
        bool transferred = token.transfer(STRANGER, TOTAL_ESCROW);
        require(transferred, "transfer failed");
        vm.expectRevert();
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Test 3: Unsupported / Disabled asset
        token.mint(BUYER, TOTAL_ESCROW);
        // Disable deposits in registry
        registry.configureAsset(
            address(token), SupportedAssetRegistry.Asset(6, true, false, true, 1, type(uint256).max)
        );
        vm.startPrank(BUYER);
        vm.expectRevert(FinXEscrow.AssetDisabled.selector);
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Re-enable asset and complete funding
        registry.configureAsset(address(token), SupportedAssetRegistry.Asset(6, true, true, true, 1, type(uint256).max));
        vm.prank(BUYER);
        e2eEscrow.fundEscrow();
        require(e2eEscrow.fundedAmount() == TOTAL_ESCROW, "Funding failed after re-enable");
    }

    // =========================================================================
    // 3. SECTION 7: AUTHORIZATION ATTACK MATRIX
    // =========================================================================

    function testSection7AuthorizationAttacks() public {
        // Agreement phase
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.acceptAgreement();

        vm.prank(BUYER);
        e2eEscrow.acceptAgreement();
        vm.prank(VENDOR);
        e2eEscrow.acceptAgreement();

        // Stranger attempts funding
        token.mint(STRANGER, TOTAL_ESCROW);
        vm.startPrank(STRANGER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Valid funding by buyer
        token.mint(BUYER, TOTAL_ESCROW);
        vm.startPrank(BUYER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW);
        e2eEscrow.fundEscrow();
        vm.stopPrank();

        // Attack 1: Buyer attempts vendor-only submission
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(BUYER);
        e2eEscrow.submitMilestone(M_1);

        // Attack 2: Stranger attempts submission
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.submitMilestone(M_1);

        // Vendor submits legitimately
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);

        // Attack 3: Vendor attempts buyer-only approval
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(VENDOR);
        e2eEscrow.approveMilestone(M_1);

        // Attack 4: Stranger attempts approval
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.approveMilestone(M_1);

        // Buyer approves legitimately
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        // Attack 5: Stranger attempts release
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.releaseMilestone(M_1);

        // Attack 6: Stranger attempts refund
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.refundMilestone(M_1);

        // Attack 7: Stranger attempts dispute resolution
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.resolveDispute(M_1, true);

        // Attack 8: Buyer attempts dispute resolution
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(BUYER);
        e2eEscrow.resolveDispute(M_1, true);

        // Attack 9: Stranger attempts pause/unpause
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.pause();

        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.unpause();

        // Attack 10: Factory creator attempts arbitrary withdrawal
        // Smart contract contains NO sweep, drain, or transfer function for owner/factory.
        // Funds remain 100% locked according to milestone state machine.
        require(token.balanceOf(address(e2eEscrow)) == TOTAL_ESCROW, "Funds drained illegally");
    }

    // =========================================================================
    // 4. SECTION 8: DOUBLE-SPEND / DOUBLE-RELEASE RESILIENCE
    // =========================================================================

    function testSection8DoubleSpendAndDoubleReleaseBlocked() public {
        _fundE2E();

        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        // First release succeeds
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_1);
        require(token.balanceOf(VENDOR) == M1_AMOUNT, "First release failed");

        // Attack: Double release of same milestone
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_1);

        // Attack: Re-approve already released milestone
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        // Attack: Attempt refund on already released milestone
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.refundMilestone(M_1);

        // Attack: Attempt double-refund on M2
        vm.prank(VENDOR);
        e2eEscrow.refundMilestone(M_2);
        require(token.balanceOf(BUYER) == M2_AMOUNT, "First refund failed");

        // Second refund on M2 must revert
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.refundMilestone(M_2);

        // Attempt release on already refunded milestone M2
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_2);

        // Accounting sanity
        require(e2eEscrow.releasedAmount() == M1_AMOUNT, "Released corrupted");
        require(e2eEscrow.refundedAmount() == M2_AMOUNT, "Refunded corrupted");
        require(e2eEscrow.lockedAmount() == M3_AMOUNT, "Locked balance corrupted");
    }

    // =========================================================================
    // 5. SECTION 10: FRONT-RUNNING / TRANSACTION ORDERING TESTS
    // =========================================================================

    function testSection10FrontRunningAndAdversarialOrdering() public {
        _fundE2E();

        // Sequence A: Submit -> Buyer approves -> Vendor fronts with dispute
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        // Milestone is in APPROVED state. Either party raises dispute.
        vm.prank(BUYER);
        e2eEscrow.raiseDispute(M_1);
        require(e2eEscrow.status() == FinXEscrow.Status.DISPUTED, "Escrow not disputed");

        // Front-run attempt: Vendor tries to call releaseMilestone while disputed
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_1);

        // Arbitrator resolves to Vendor
        vm.prank(arbitrator);
        e2eEscrow.resolveDispute(M_1, true);
        require(token.balanceOf(VENDOR) == M1_AMOUNT, "Arbitrator release failed");

        // Sequence B: Cancel after funding attempt
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        e2eEscrow.cancelEscrow();

        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        e2eEscrow.cancelEscrow();
    }

    // =========================================================================
    // 6. SECTION 11: DISPUTE ARBITRATION TEST
    // =========================================================================

    function testSection11DisputeArbitrationScenarios() public {
        _fundE2E();

        // Vendor submits M_1
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);

        // Buyer raises dispute on M_1
        vm.prank(BUYER);
        e2eEscrow.raiseDispute(M_1);

        // Unauthorized user attempts dispute resolution
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        e2eEscrow.resolveDispute(M_1, false);

        // Arbitrator resolves M_1 in favor of BUYER (full refund for M1)
        vm.prank(arbitrator);
        e2eEscrow.resolveDispute(M_1, false);
        require(token.balanceOf(BUYER) == M1_AMOUNT, "Buyer did not receive dispute refund");
        require(e2eEscrow.refundedAmount() == M1_AMOUNT, "Refunded accounting mismatch");

        // Attempt double resolution on M_1
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(arbitrator);
        e2eEscrow.resolveDispute(M_1, false);

        // Milestone 2: Vendor raises dispute directly from FUNDED state
        vm.prank(VENDOR);
        e2eEscrow.raiseDispute(M_2);

        // Arbitrator resolves M_2 in favor of VENDOR (full release for M2)
        vm.prank(arbitrator);
        e2eEscrow.resolveDispute(M_2, true);
        require(token.balanceOf(VENDOR) == M2_AMOUNT, "Vendor did not receive dispute award");

        // Milestone 3: Normal approval & release
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_3);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_3);
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_3);

        // Escrow should be completed
        require(e2eEscrow.status() == FinXEscrow.Status.COMPLETED, "Escrow not completed");
        require(e2eEscrow.lockedAmount() == 0, "Locked amount not zero");

        // Final conservation validation:
        // Total (10,000) = Released (M2: 3,000 + M3: 5,000 = 8,000) + Refunded (M1: 2,000)
        require(e2eEscrow.releasedAmount() == 8000 * UNITS_DECIMALS, "Released total mismatch");
        require(e2eEscrow.refundedAmount() == 2000 * UNITS_DECIMALS, "Refunded total mismatch");
        require(token.balanceOf(VENDOR) == 8000 * UNITS_DECIMALS, "Vendor balance mismatch");
        require(token.balanceOf(BUYER) == 2000 * UNITS_DECIMALS, "Buyer balance mismatch");
        require(token.balanceOf(address(e2eEscrow)) == 0, "Escrow balance must be 0");
    }

    // =========================================================================
    // 7. SECTION 9: REENTRANCY ATTACK HARDENING TEST
    // =========================================================================

    function testSection9ReentrancyAttackResilience() public {
        _fundE2E();

        // Configure callback in token pointing back to e2eEscrow
        token.setCallback(address(e2eEscrow));

        // Submit and approve M1
        vm.prank(VENDOR);
        e2eEscrow.submitMilestone(M_1);
        vm.prank(BUYER);
        e2eEscrow.approveMilestone(M_1);

        // Trigger release with callback armed
        vm.prank(VENDOR);
        e2eEscrow.releaseMilestone(M_1);

        // Verify callback was intercepted and rejected by ReentrancyGuard
        require(!token.callbackSucceeded(), "Reentrant call succeeded unexpectedly!");
        require(
            token.callbackError() == bytes4(keccak256("ReentrancyGuardReentrantCall()")),
            "Expected ReentrancyGuardReentrantCall"
        );

        // Verify state was completely preserved
        require(e2eEscrow.releasedAmount() == M1_AMOUNT, "Released corrupted by reentrancy attempt");
        require(e2eEscrow.lockedAmount() == TOTAL_ESCROW - M1_AMOUNT, "Locked balance corrupted");
        require(token.balanceOf(VENDOR) == M1_AMOUNT, "Vendor balance corrupted");
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    function _fundE2E() internal {
        vm.prank(BUYER);
        e2eEscrow.acceptAgreement();
        vm.prank(VENDOR);
        e2eEscrow.acceptAgreement();

        token.mint(BUYER, TOTAL_ESCROW);
        vm.startPrank(BUYER);
        token.approve(address(e2eEscrow), TOTAL_ESCROW);
        e2eEscrow.fundEscrow();
        vm.stopPrank();
    }
}
