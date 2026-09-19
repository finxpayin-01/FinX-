// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {DeployAmoy} from "../script/DeployAmoy.s.sol";
import {TestnetCouncil} from "../src/TestnetCouncil.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {MockUSDC} from "../src/MockUSDC.sol";

interface Vm {
    function prank(address) external;
    function startPrank(address) external;
    function stopPrank() external;
}

/**
 * @title SmokeTest
 * @notice Tests the complete end-to-end lifecycle on the deployed Amoy contract architecture.
 */
contract SmokeTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));

    address internal constant BUYER = address(0xB0B);
    address internal constant VENDOR = address(0xCAFE);

    DeployAmoy internal deployer;
    TestnetCouncil internal governance;
    TestnetCouncil internal disputeCouncil;
    TestnetCouncil internal pauseCouncil;
    SupportedAssetRegistry internal registry;
    FinXEscrowFactory internal factory;
    MockUSDC internal token;
    address internal creator;

    function setUp() public {
        deployer = new DeployAmoy();
        deployer.run();

        governance = deployer.governance();
        disputeCouncil = deployer.disputeCouncil();
        pauseCouncil = deployer.pauseCouncil();
        registry = deployer.registry();
        factory = deployer.factory();
        token = MockUSDC(deployer.testToken());
        creator = governance.owner();
    }

    function testEscrowEndToEndReleaseAndRefundLifecycle() public {
        // 1. Prepare terms
        bytes32 escrowId = keccak256("smoke-escrow-1");
        bytes32 dealIdHash = keccak256("smoke-deal-1");
        bytes32 m1 = keccak256("milestone-1");
        bytes32 m2 = keccak256("milestone-2");

        bytes32[] memory ids = new bytes32[](2);
        ids[0] = m1;
        ids[1] = m2;

        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 300_000_000; // 300 TEST-USDC
        amounts[1] = 200_000_000; // 200 TEST-USDC
        uint256 total = 500_000_000; // 500 TEST-USDC

        // 2. Creator deploys escrow through factory
        vm.prank(creator);
        address escrowAddress = factory.createEscrow(
            escrowId,
            dealIdHash,
            BUYER,
            VENDOR,
            address(token),
            ids,
            amounts
        );

        require(escrowAddress != address(0), "Escrow deployment failed");
        require(escrowAddress.code.length > 0, "Escrow has no bytecode");
        require(factory.escrows(escrowId) == escrowAddress, "Factory lookup mismatch");

        FinXEscrow escrow = FinXEscrow(escrowAddress);
        require(escrow.status() == FinXEscrow.Status.CREATED, "Invalid initial status");

        // 3. Both parties accept
        vm.prank(BUYER);
        escrow.acceptAgreement();
        require(escrow.buyerAccepted(), "Buyer acceptance not recorded");

        vm.prank(VENDOR);
        escrow.acceptAgreement();
        require(escrow.vendorAccepted(), "Vendor acceptance not recorded");
        require(escrow.status() == FinXEscrow.Status.AWAITING_FUNDING, "Status should be AWAITING_FUNDING");

        // 4. Mint test tokens to buyer and fund escrow
        vm.prank(token.owner());
        token.mint(BUYER, total);
        require(token.balanceOf(BUYER) >= total, "Buyer mint failed");

        vm.startPrank(BUYER);
        token.approve(escrowAddress, total);
        escrow.fundEscrow();
        vm.stopPrank();

        require(escrow.status() == FinXEscrow.Status.FUNDED, "Status should be FUNDED");
        require(escrow.fundedAmount() == total, "Funded amount mismatch");
        require(escrow.lockedAmount() == total, "Locked amount mismatch");
        require(token.balanceOf(escrowAddress) == total, "Escrow token balance mismatch");

        // 5. Milestone 1 lifecycle: Submit -> Approve -> Release
        vm.prank(VENDOR);
        escrow.submitMilestone(m1);
        require(escrow.status() == FinXEscrow.Status.ACTIVE, "Status should be ACTIVE");

        vm.prank(BUYER);
        escrow.approveMilestone(m1);

        uint256 vendorBefore = token.balanceOf(VENDOR);
        vm.prank(BUYER);
        escrow.releaseMilestone(m1);

        require(token.balanceOf(VENDOR) == vendorBefore + amounts[0], "Vendor did not receive milestone 1 release");
        require(escrow.releasedAmount() == amounts[0], "Released amount mismatch");

        // 6. Milestone 2 lifecycle: Voluntary refund by vendor
        uint256 buyerBefore = token.balanceOf(BUYER);
        vm.prank(VENDOR);
        escrow.refundMilestone(m2);

        require(token.balanceOf(BUYER) == buyerBefore + amounts[1], "Buyer did not receive milestone 2 refund");
        require(escrow.refundedAmount() == amounts[1], "Refunded amount mismatch");

        // 7. Verify final completed state
        require(escrow.status() == FinXEscrow.Status.COMPLETED, "Escrow status should be COMPLETED");
        require(escrow.closedMilestones() == 2, "All milestones should be closed");
        require(escrow.lockedAmount() == 0, "Escrow should have 0 locked funds");
        require(token.balanceOf(escrowAddress) == 0, "Escrow contract balance should be 0");
    }

    function testArbitratorDisputeResolutionLifecycle() public {
        bytes32 escrowId = keccak256("dispute-escrow-1");
        bytes32 dealIdHash = keccak256("dispute-deal-1");
        bytes32 m1 = keccak256("dispute-m-1");

        bytes32[] memory ids = new bytes32[](1);
        ids[0] = m1;

        uint256[] memory amounts = new uint256[](1);
        amounts[0] = 100_000_000;

        vm.prank(creator);
        address escrowAddress = factory.createEscrow(
            escrowId,
            dealIdHash,
            BUYER,
            VENDOR,
            address(token),
            ids,
            amounts
        );

        FinXEscrow escrow = FinXEscrow(escrowAddress);

        // Accept and fund
        vm.prank(BUYER);
        escrow.acceptAgreement();
        vm.prank(VENDOR);
        escrow.acceptAgreement();

        vm.prank(token.owner());
        token.mint(BUYER, 100_000_000);
        vm.startPrank(BUYER);
        token.approve(escrowAddress, 100_000_000);
        escrow.fundEscrow();
        vm.stopPrank();

        // Raise dispute
        vm.prank(BUYER);
        escrow.raiseDispute(m1);
        require(escrow.status() == FinXEscrow.Status.DISPUTED, "Status should be DISPUTED");
        require(escrow.disputedMilestones() == 1, "Disputed milestone count mismatch");

        // Arbitrator (DisputeCouncil) resolves dispute to vendor via authorized execution
        address councilOwner = disputeCouncil.owner();
        uint256 vendorBefore = token.balanceOf(VENDOR);

        vm.prank(councilOwner);
        disputeCouncil.execute(
            escrowAddress,
            abi.encodeCall(escrow.resolveDispute, (m1, true))
        );

        require(token.balanceOf(VENDOR) == vendorBefore + 100_000_000, "Vendor should receive resolved funds");
        require(escrow.status() == FinXEscrow.Status.COMPLETED, "Escrow should be COMPLETED");
    }

    function testPauseCouncilLifecycle() public {
        // Test global pause on factory via pauseCouncil
        address councilOwner = pauseCouncil.owner();

        require(!factory.paused(), "Factory should initially not be paused");

        vm.prank(councilOwner);
        pauseCouncil.execute(
            address(factory),
            abi.encodeCall(factory.pause, ())
        );

        require(factory.paused(), "Factory should now be paused");

        // Unpause via governance (DEFAULT_ADMIN_ROLE)
        address govOwner = governance.owner();
        vm.prank(govOwner);
        governance.execute(
            address(factory),
            abi.encodeCall(factory.unpause, ())
        );

        require(!factory.paused(), "Factory should now be unpaused");
    }
}
