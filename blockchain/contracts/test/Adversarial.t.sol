// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;
import {EscrowFixture, TestToken} from "./Fixtures.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";

contract AdversarialTest is EscrowFixture {
    function testFeeTokenFundingRollsBack() public {
        _accept();
        token.mint(BUYER, 500);
        token.setFee(true);
        vm.prank(BUYER);
        token.approve(address(escrow), 500);
        vm.expectRevert(FinXEscrow.TransferAmountMismatch.selector);
        vm.prank(BUYER);
        escrow.fundEscrow();
        require(escrow.fundedAmount() == 0 && token.balanceOf(BUYER) == 500, "atomic funding");
        require(_state(M1) == FinXEscrow.MilestoneStatus.PENDING, "state rollback");
    }

    function testFeeTokenReleaseRollsBack() public {
        _fund();
        _approve(M1);
        token.setFee(true);
        vm.expectRevert(FinXEscrow.TransferAmountMismatch.selector);
        vm.prank(VENDOR);
        escrow.releaseMilestone(M1);
        require(escrow.releasedAmount() == 0 && token.balanceOf(VENDOR) == 0, "atomic release");
        require(_state(M1) == FinXEscrow.MilestoneStatus.APPROVED, "approval retained");
        _conserved();
    }

    function testTokenTransferFailureRollsBack() public {
        _fund();
        _approve(M1);
        token.setFail(true);
        vm.expectRevert();
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        require(escrow.closedMilestones() == 0 && escrow.releasedAmount() == 0, "failed transfer");
        token.setFail(false);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        _conserved();
    }

    function testNonReturningTokenSupported() public {
        token.setNoReturn(true);
        _fund();
        _approve(M1);
        vm.prank(VENDOR);
        escrow.releaseMilestone(M1);
        require(token.balanceOf(VENDOR) == 200, "no-return token");
        _conserved();
    }

    function testTokenCallbackReentrancyBlockedOnFundingAndRelease() public {
        token.setCallback(address(escrow));
        _fund();
        require(
            !token.callbackSucceeded() && token.callbackError() == bytes4(keccak256("ReentrancyGuardReentrantCall()")),
            "fund reentry"
        );
        _approve(M1);
        vm.prank(BUYER);
        escrow.releaseMilestone(M1);
        require(
            !token.callbackSucceeded() && token.callbackError() == bytes4(keccak256("ReentrancyGuardReentrantCall()")),
            "release reentry"
        );
        _conserved();
    }

    function testWrongTokenAndDonationsNeverCreditFunding() public {
        TestToken wrong = new TestToken(18);
        wrong.mint(address(escrow), 123);
        token.mint(address(escrow), 19);
        require(escrow.fundedAmount() == 0, "donation credited");
        _fund();
        vm.prank(VENDOR);
        escrow.refundMilestone(M1);
        vm.prank(VENDOR);
        escrow.refundMilestone(M2);
        require(token.balanceOf(address(escrow)) == 19 && wrong.balanceOf(address(escrow)) == 123, "donation spent");
        require(token.balanceOf(BUYER) == 500, "allocation altered");
        _conserved();
    }

    function testFactoryRejectsUnauthorizedDuplicateAndBadTerms() public {
        bytes32 existingEscrow = escrow.escrowId();
        bytes32 existingDeal = escrow.dealIdHash();
        bytes32[] memory ids = new bytes32[](2);
        ids[0] = M1;
        ids[1] = M2;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 1;
        amounts[1] = 2;
        vm.expectRevert();
        vm.prank(STRANGER);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, VENDOR, address(token), ids, amounts);
        vm.expectRevert(FinXEscrowFactory.AlreadyRegistered.selector);
        factory.createEscrow(existingEscrow, keccak256("new"), BUYER, VENDOR, address(token), ids, amounts);
        vm.expectRevert(FinXEscrowFactory.AlreadyRegistered.selector);
        factory.createEscrow(keccak256("new"), existingDeal, BUYER, VENDOR, address(token), ids, amounts);
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, BUYER, address(token), ids, amounts);
        ids[1] = M1;
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, VENDOR, address(token), ids, amounts);
        ids[1] = M2;
        amounts[0] = 0;
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, VENDOR, address(token), ids, amounts);
    }

    function testFactoryRejectsUnapprovedAssetAndOversizedSchedule() public {
        bytes32[] memory ids = new bytes32[](1);
        ids[0] = M1;
        uint256[] memory amounts = new uint256[](1);
        amounts[0] = 1;
        TestToken wrong = new TestToken(18);
        vm.expectRevert(FinXEscrow.AssetDisabled.selector);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, VENDOR, address(wrong), ids, amounts);
        ids = new bytes32[](65);
        amounts = new uint256[](65);
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(keccak256("x"), keccak256("y"), BUYER, VENDOR, address(token), ids, amounts);
    }

    function testRegistryEnforcesAuthorityDecimalsAndLimits() public {
        SupportedAssetRegistry.Asset memory a = SupportedAssetRegistry.Asset(6, true, true, true, 100, 600);
        vm.expectRevert();
        vm.prank(STRANGER);
        registry.configureAsset(address(token), a);
        a.decimals = 18;
        vm.expectRevert(SupportedAssetRegistry.InvalidAsset.selector);
        registry.configureAsset(address(token), a);
        a.decimals = 6;
        registry.configureAsset(address(token), a);
        require(!registry.canDeposit(address(token), 99) && registry.canDeposit(address(token), 100), "min");
        require(registry.canDeposit(address(token), 600) && !registry.canDeposit(address(token), 601), "max");
    }

    function testFuzzAmountsAndOutcome(uint128 a, uint128 b, bool refundFirst, bool disputeSecond) public {
        uint256 first = uint256(a) + 1;
        uint256 second = uint256(b) + 1;
        escrow = _create(first, second);
        _fund();
        if (refundFirst) {
            vm.prank(VENDOR);
            escrow.refundMilestone(M1);
        } else {
            _approve(M1);
            vm.prank(BUYER);
            escrow.releaseMilestone(M1);
        }
        require(escrow.lockedAmount() == second, "allocation isolation");
        if (disputeSecond) {
            vm.prank(BUYER);
            escrow.raiseDispute(M2);
            vm.prank(arbitrator);
            escrow.resolveDispute(M2, false);
        } else {
            _approve(M2);
            vm.prank(VENDOR);
            escrow.releaseMilestone(M2);
        }
        require(escrow.releasedAmount() + escrow.refundedAmount() == first + second, "conservation");
        require(token.balanceOf(address(escrow)) == 0, "residual");
        _conserved();
    }
}
