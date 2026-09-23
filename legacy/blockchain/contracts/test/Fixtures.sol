// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {FinXEscrow} from "../src/FinXEscrow.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";

interface Vm {
    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }
    function prank(address) external;
    function startPrank(address) external;
    function stopPrank() external;
    function expectRevert() external;
    function expectRevert(bytes4) external;
    function recordLogs() external;
    function getRecordedLogs() external returns (Log[] memory);
    function warp(uint256) external;
}

// Test-only actors. These are NOT multisig implementations or deployment examples.
contract TestCouncil {}

contract TestToken is ERC20 {
    uint8 private immutable precision;
    bool public fee;
    bool public fail;
    bool public noReturn;
    address public callback;
    bool public callbackSucceeded;
    bytes4 public callbackError;
    bool private entered;

    constructor(uint8 decimals_) ERC20("TEST ONLY", "TEST") {
        precision = decimals_;
    }

    function decimals() public view override returns (uint8) {
        return precision;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function setFee(bool value) external {
        fee = value;
    }

    function setFail(bool value) external {
        fail = value;
    }

    function setNoReturn(bool value) external {
        noReturn = value;
    }

    function setCallback(address value) external {
        callback = value;
    }

    function transfer(address to, uint256 amount) public override returns (bool) {
        if (fail) return false;
        super.transfer(to, amount);
        if (noReturn) assembly { return(0, 0) }
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) public override returns (bool) {
        if (fail) return false;
        super.transferFrom(from, to, amount);
        if (noReturn) assembly { return(0, 0) }
        return true;
    }

    function _update(address from, address to, uint256 amount) internal override {
        if (fee && from != address(0) && to != address(0) && amount > 1) {
            super._update(from, to, amount - 1);
            super._update(from, address(0), 1);
        } else {
            super._update(from, to, amount);
        }
        if (callback != address(0) && !entered && from != address(0)) {
            entered = true;
            bytes memory result;
            (callbackSucceeded, result) = callback.call(abi.encodeCall(FinXEscrow.fundEscrow, ()));
            if (result.length >= 4) callbackError = bytes4(result);
            entered = false;
        }
    }
}

abstract contract EscrowFixture {
    Vm internal constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    address internal constant BUYER = address(0xB0B);
    address internal constant VENDOR = address(0xCAFE);
    address internal constant STRANGER = address(0xBAD);
    bytes32 internal constant M1 = keccak256("milestone-1");
    bytes32 internal constant M2 = keccak256("milestone-2");
    TestToken internal token;
    SupportedAssetRegistry internal registry;
    FinXEscrowFactory internal factory;
    FinXEscrow internal escrow;
    address internal arbitrator;
    address internal council;
    uint256 private sequence;

    function setUp() public virtual {
        arbitrator = address(new TestCouncil());
        council = address(new TestCouncil());
        token = new TestToken(6);
        registry = new SupportedAssetRegistry(address(this), 1 days);
        _asset(true, true, true);
        factory = new FinXEscrowFactory(address(this), 1 days, registry, arbitrator, council);
        factory.grantRole(factory.CREATOR_ROLE(), address(this));
        escrow = _create(200, 300);
    }

    function _asset(bool enabled, bool deposit, bool withdrawal) internal {
        registry.configureAsset(
            address(token), SupportedAssetRegistry.Asset(6, enabled, deposit, withdrawal, 1, type(uint256).max)
        );
    }

    function _create(uint256 a, uint256 b) internal returns (FinXEscrow) {
        bytes32[] memory ids = new bytes32[](2);
        ids[0] = M1;
        ids[1] = M2;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = a;
        amounts[1] = b;
        ++sequence;
        return FinXEscrow(
            factory.createEscrow(
                keccak256(abi.encode("escrow", sequence)),
                keccak256(abi.encode("deal", sequence)),
                BUYER,
                VENDOR,
                address(token),
                ids,
                amounts
            )
        );
    }

    function _accept() internal {
        vm.prank(BUYER);
        escrow.acceptAgreement();
        vm.prank(VENDOR);
        escrow.acceptAgreement();
    }

    function _fund() internal {
        _accept();
        uint256 total = escrow.totalAmount();
        token.mint(BUYER, total);
        vm.startPrank(BUYER);
        token.approve(address(escrow), total);
        escrow.fundEscrow();
        vm.stopPrank();
    }

    function _approve(bytes32 id) internal {
        vm.prank(VENDOR);
        escrow.submitMilestone(id);
        vm.prank(BUYER);
        escrow.approveMilestone(id);
    }

    function _state(bytes32 id) internal view returns (FinXEscrow.MilestoneStatus s) {
        (, s,,,) = escrow.milestones(id);
    }

    function _conserved() internal view {
        require(escrow.releasedAmount() + escrow.refundedAmount() <= escrow.fundedAmount(), "overspent");
        require(escrow.fundedAmount() <= escrow.totalAmount(), "overfunded");
        require(token.balanceOf(address(escrow)) >= escrow.lockedAmount(), "insolvent");
    }
}
