// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;
import {EscrowFixture, TestToken, Vm} from "./Fixtures.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";

contract FinXEscrowTest is EscrowFixture {
    function testCreationAndAcceptance() public {
        require(escrow.factory() == address(factory) && escrow.totalAmount() == 500, "terms");
        require(escrow.milestoneCount() == 2 && escrow.createdAt() == block.timestamp, "metadata");
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        escrow.acceptAgreement();
        vm.prank(BUYER);
        escrow.acceptAgreement();
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.acceptAgreement();
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.fundEscrow();
        vm.prank(VENDOR);
        escrow.acceptAgreement();
        require(escrow.status() == FinXEscrow.Status.AWAITING_FUNDING, "acceptance");
    }

    function testFullLifecycleAndEvents() public {
        vm.recordLogs();
        _fund();
        Vm.Log[] memory logs = vm.getRecordedLogs();
        bool found;
        for (uint256 i; i < logs.length; ++i) {
            if (
                logs[i].emitter == address(escrow)
                    && logs[i].topics[0] == keccak256("EscrowFunded(bytes32,address,address,uint256)")
            ) {
                require(logs[i].topics[1] == escrow.escrowId(), "event id");
                require(logs[i].topics[2] == bytes32(uint256(uint160(address(token)))), "event token");
                require(logs[i].topics[3] == bytes32(uint256(uint160(BUYER))), "event buyer");
                require(abi.decode(logs[i].data, (uint256)) == 500, "event amount");
                found = true;
            }
        }
        require(found, "fund event missing");
        vm.warp(100);
        _approve(M1);
        vm.prank(VENDOR);
        escrow.releaseMilestone(M1);
        require(token.balanceOf(VENDOR) == 200 && escrow.lockedAmount() == 300, "isolation");
        (,, uint256 submitted, uint256 approved, uint256 released) = escrow.milestones(M1);
        require(submitted == 100 && approved == 100 && released == 100, "timestamps");
        _approve(M2);
        vm.prank(BUYER);
        escrow.releaseMilestone(M2);
        require(escrow.status() == FinXEscrow.Status.COMPLETED && token.balanceOf(VENDOR) == 500, "completion");
        _conserved();
    }

    function testPartialFundingAndOverfundingRejected() public {
        _accept();
        token.mint(BUYER, 1000);
        vm.startPrank(BUYER);
        token.approve(address(escrow), 499);
        vm.expectRevert();
        escrow.fundEscrow();
        require(escrow.fundedAmount() == 0, "partial credited");
        token.approve(address(escrow), 1000);
        escrow.fundEscrow();
        require(escrow.fundedAmount() == 500 && token.balanceOf(BUYER) == 500, "overfund");
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        escrow.fundEscrow();
        vm.stopPrank();
    }

    function testUnauthorizedAndPrematureMoneyMovement() public {
        _fund();
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(BUYER);
        escrow.submitMilestone(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(VENDOR);
        escrow.approveMilestone(M1);
        _approve(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(STRANGER);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        vm.prank(BUYER);
        escrow.refundMilestone(M1);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.cancelEscrow();
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        vm.prank(VENDOR);
        escrow.submitMilestone(bytes32(0));
        _conserved();
    }

    function testNoDoubleReleaseOrRefund() public {
        _fund();
        _approve(M1);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        escrow.refundMilestone(M1);
        vm.prank(VENDOR);
        escrow.refundMilestone(M2);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        escrow.refundMilestone(M2);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.raiseDispute(M2);
        require(escrow.releasedAmount() == 200 && escrow.refundedAmount() == 300, "totals");
        _conserved();
    }

    function testDisputeIsolatesMilestoneAndArbitration() public {
        _fund();
        _approve(M1);
        vm.prank(VENDOR);
        escrow.raiseDispute(M1);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        escrow.resolveDispute(M1, true);
        _approve(M2);
        vm.prank(VENDOR);
        escrow.releaseMilestone(M2);
        require(escrow.status() == FinXEscrow.Status.DISPUTED, "lost dispute");
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, false);
        require(token.balanceOf(BUYER) == 200 && token.balanceOf(VENDOR) == 300, "resolution");
        require(escrow.status() == FinXEscrow.Status.COMPLETED && escrow.disputedMilestones() == 0, "final status");
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, true);
        _conserved();
    }

    function testArbitratorCanOnlyResolveDisputedAllocation() public {
        _fund();
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, true);
        vm.prank(BUYER);
        escrow.raiseDispute(M1);
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(VENDOR);
        escrow.raiseDispute(M1);
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, true);
        require(token.balanceOf(VENDOR) == 200 && escrow.lockedAmount() == 300, "arbitrator scope");
    }

    function testFullRefundAndCancellation() public {
        vm.prank(VENDOR);
        escrow.cancelEscrow();
        vm.expectRevert(FinXEscrow.InvalidState.selector);
        vm.prank(BUYER);
        escrow.acceptAgreement();
        escrow = _create(200, 300);
        _fund();
        vm.prank(VENDOR);
        escrow.refundMilestone(M1);
        vm.prank(BUYER);
        escrow.raiseDispute(M2);
        vm.prank(arbitrator);
        escrow.resolveDispute(M2, false);
        require(escrow.status() == FinXEscrow.Status.REFUNDED && token.balanceOf(BUYER) == 500, "refund");
        _conserved();
    }

    function testPauseRetainsDisputeRights() public {
        _fund();
        _approve(M1);
        vm.expectRevert(FinXEscrow.Unauthorized.selector);
        escrow.pause();
        vm.prank(council);
        escrow.pause();
        vm.expectRevert();
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        vm.prank(BUYER);
        escrow.raiseDispute(M1);
        vm.expectRevert();
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, false);
        vm.prank(council);
        escrow.unpause();
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, false);
        vm.prank(council);
        factory.pause();
        vm.expectRevert(FinXEscrow.ProtocolPaused.selector);
        vm.prank(VENDOR);
        escrow.refundMilestone(M2);
        factory.unpause();
        vm.prank(VENDOR);
        escrow.refundMilestone(M2);
        _conserved();
    }

    function testAssetCircuitBreakers() public {
        _accept();
        token.mint(BUYER, 500);
        vm.prank(BUYER);
        token.approve(address(escrow), 500);
        _asset(true, false, true);
        vm.expectRevert(FinXEscrow.AssetDisabled.selector);
        vm.prank(BUYER);
        escrow.fundEscrow();
        _asset(true, true, true);
        vm.prank(BUYER);
        escrow.fundEscrow();
        _approve(M1);
        _asset(true, true, false);
        vm.expectRevert(FinXEscrow.AssetDisabled.selector);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        require(escrow.releasedAmount() == 0, "disabled release");
        _asset(true, false, true);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        _conserved();
    }
}
