// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;
import {EscrowFixture, Vm, TestToken} from "./Fixtures.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {InvariantTargets} from "./InvariantTargets.sol";

contract EscrowHandler {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    FinXEscrow public immutable escrow;
    TestToken public immutable token;
    uint256 public successfulCalls;

    constructor(FinXEscrow e, TestToken t) {
        escrow = e;
        token = t;
    }

    function step(uint8 operation, uint8 milestone, bool outcome) external {
        bytes32 id = escrow.milestoneIds(uint256(milestone) % 2);
        uint8 op = operation % 7;
        address actor;
        bytes memory data;
        if (op == 0) {
            actor = escrow.vendor();
            data = abi.encodeCall(FinXEscrow.submitMilestone, (id));
        } else if (op == 1) {
            actor = escrow.buyer();
            data = abi.encodeCall(FinXEscrow.approveMilestone, (id));
        } else if (op == 2) {
            actor = escrow.vendor();
            data = abi.encodeCall(FinXEscrow.releaseMilestone, (id));
        } else if (op == 3) {
            actor = escrow.vendor();
            data = abi.encodeCall(FinXEscrow.refundMilestone, (id));
        } else if (op == 4) {
            actor = escrow.buyer();
            data = abi.encodeCall(FinXEscrow.raiseDispute, (id));
        } else if (op == 5) {
            actor = escrow.arbitrator();
            data = abi.encodeCall(FinXEscrow.resolveDispute, (id, outcome));
        } else {
            actor = escrow.buyer();
            data = abi.encodeCall(FinXEscrow.fundEscrow, ());
        }
        vm.prank(actor);
        (bool success,) = address(escrow).call(data);
        if (success) ++successfulCalls;
    }
}

contract EscrowInvariantTest is EscrowFixture, InvariantTargets {
    EscrowHandler public handler;
    address[] private targets;

    function setUp() public override {
        super.setUp();
        _fund();
        handler = new EscrowHandler(escrow, token);
        targets.push(address(handler));
    }

    // Foundry's invariant discovery interface; no forge-std dependency required.
    function targetContracts() public view returns (address[] memory) {
        return targets;
    }

    function invariantConservationAndAllocation() public view {
        _conserved();
        require(token.balanceOf(address(escrow)) == escrow.lockedAmount(), "balance");
        require(token.balanceOf(VENDOR) == escrow.releasedAmount(), "vendor");
        require(token.balanceOf(BUYER) == escrow.refundedAmount(), "buyer");
        uint256 released;
        uint256 refunded;
        uint256 closed;
        uint256 disputed;
        for (uint256 i; i < 2; ++i) {
            (uint256 amount, FinXEscrow.MilestoneStatus s,,,) = escrow.milestones(escrow.milestoneIds(i));
            if (s == FinXEscrow.MilestoneStatus.RELEASED) {
                released += amount;
                ++closed;
            }
            if (s == FinXEscrow.MilestoneStatus.REFUNDED) {
                refunded += amount;
                ++closed;
            }
            if (s == FinXEscrow.MilestoneStatus.DISPUTED) ++disputed;
        }
        require(released == escrow.releasedAmount() && refunded == escrow.refundedAmount(), "milestone totals");
        require(closed == escrow.closedMilestones() && disputed == escrow.disputedMilestones(), "counters");
        if (closed == 2) require(escrow.lockedAmount() == 0, "terminal balance");
        if (disputed != 0) require(escrow.status() == FinXEscrow.Status.DISPUTED, "dispute status");
    }
}
