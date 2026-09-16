// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;
import {EscrowFixture, TestCouncil, Vm} from "./Fixtures.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";

contract BoundaryTest is EscrowFixture {
    function testMaximumScheduleAndCreationEvent() public {
        bytes32[] memory ids = new bytes32[](64);
        uint256[] memory amounts = new uint256[](64);
        for (uint256 i; i < 64; ++i) {
            ids[i] = bytes32(i + 1);
            amounts[i] = 1;
        }
        vm.recordLogs();
        escrow = FinXEscrow(
            factory.createEscrow(keccak256("64"), keccak256("deal64"), BUYER, VENDOR, address(token), ids, amounts)
        );
        Vm.Log[] memory logs = vm.getRecordedLogs();
        require(
            logs[logs.length - 1].topics[0]
                == keccak256("EscrowCreated(bytes32,bytes32,address,address,address,address,uint256,uint256)"),
            "creation event"
        );
        _fund();
        for (uint256 i; i < 64; ++i) {
            vm.prank(VENDOR);
            escrow.refundMilestone(ids[i]);
        }
        require(escrow.refundedAmount() == 64 && escrow.status() == FinXEscrow.Status.REFUNDED, "max schedule");
    }

    function testZeroEmptyAndOverflowTerms() public {
        bytes32[] memory ids = new bytes32[](0);
        uint256[] memory amounts = new uint256[](0);
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(keccak256("z"), keccak256("d"), BUYER, VENDOR, address(token), ids, amounts);
        ids = new bytes32[](1);
        amounts = new uint256[](1);
        ids[0] = M1;
        amounts[0] = 1;
        vm.expectRevert(FinXEscrow.InvalidTerms.selector);
        factory.createEscrow(bytes32(0), keccak256("d"), BUYER, VENDOR, address(token), ids, amounts);
        vm.expectRevert();
        _create(type(uint256).max, 1);
    }

    function testFactoryPausePreventsCreationAndFunding() public {
        _accept();
        token.mint(BUYER, 500);
        vm.prank(BUYER);
        token.approve(address(escrow), 500);
        vm.prank(council);
        factory.pause();
        vm.expectRevert(FinXEscrow.ProtocolPaused.selector);
        vm.prank(BUYER);
        escrow.fundEscrow();
        vm.expectRevert();
        _create(1, 1);
        vm.expectRevert();
        vm.prank(council);
        factory.unpause();
        factory.unpause();
        vm.prank(BUYER);
        escrow.fundEscrow();
        _conserved();
    }

    function testGovernanceDelayAndRoleSeparation() public {
        address nextGovernance = address(new TestCouncil());
        bytes32 adminRole = factory.DEFAULT_ADMIN_ROLE();
        vm.expectRevert();
        factory.grantRole(adminRole, STRANGER);
        factory.beginDefaultAdminTransfer(nextGovernance);
        vm.expectRevert();
        vm.prank(nextGovernance);
        factory.acceptDefaultAdminTransfer();
        vm.warp(block.timestamp + 1 days + 1);
        vm.prank(nextGovernance);
        factory.acceptDefaultAdminTransfer();
        require(factory.defaultAdmin() == nextGovernance, "delayed governance");
        vm.expectRevert(FinXEscrowFactory.InvalidConfiguration.selector);
        new FinXEscrowFactory(address(this), 1 days, registry, council, council);
        vm.expectRevert(SupportedAssetRegistry.InvalidAsset.selector);
        new SupportedAssetRegistry(BUYER, 1 days);
        require(escrow.arbitrator() == arbitrator && escrow.emergencyCouncil() == council, "terms changed");
    }

    function testDisputeCountersAndResume() public {
        _fund();
        vm.prank(BUYER);
        escrow.raiseDispute(M1);
        vm.prank(VENDOR);
        escrow.raiseDispute(M2);
        vm.prank(arbitrator);
        escrow.resolveDispute(M1, true);
        require(escrow.disputedMilestones() == 1 && escrow.status() == FinXEscrow.Status.DISPUTED, "remaining dispute");
        vm.prank(arbitrator);
        escrow.resolveDispute(M2, false);
        _conserved();
        require(escrow.disputedMilestones() == 0 && escrow.status() == FinXEscrow.Status.COMPLETED, "closed");
    }
}
