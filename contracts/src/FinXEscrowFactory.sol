// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {
    AccessControlDefaultAdminRules
} from "@openzeppelin/contracts/access/extensions/AccessControlDefaultAdminRules.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {FinXEscrow} from "./FinXEscrow.sol";
import {SupportedAssetRegistry} from "./SupportedAssetRegistry.sol";

/// @notice Version 1 factory; only new contracts are configurable, never funded terms.
contract FinXEscrowFactory is AccessControlDefaultAdminRules, Pausable {
    bytes32 public constant CREATOR_ROLE = keccak256("CREATOR_ROLE");
    bytes32 public constant PAUSER_ROLE = keccak256("PAUSER_ROLE");
    uint256 public constant VERSION = 1;
    SupportedAssetRegistry public immutable registry;
    address public immutable arbitrator;
    address public immutable emergencyCouncil;
    mapping(bytes32 => address) public escrows;
    mapping(bytes32 => bool) public registeredDeals;

    error InvalidConfiguration();
    error AlreadyRegistered();
    event EscrowCreated(
        bytes32 indexed escrowId,
        bytes32 indexed dealIdHash,
        address indexed escrow,
        address buyer,
        address vendor,
        address token,
        uint256 totalAmount,
        uint256 version
    );

    constructor(
        address governance,
        uint48 adminDelay,
        SupportedAssetRegistry assetRegistry,
        address disputeCouncil,
        address pauseCouncil
    ) AccessControlDefaultAdminRules(adminDelay, governance) {
        if (
            governance.code.length == 0 || adminDelay == 0 || address(assetRegistry).code.length == 0
                || disputeCouncil.code.length == 0 || pauseCouncil.code.length == 0 || governance == disputeCouncil
                || governance == pauseCouncil || disputeCouncil == pauseCouncil
        ) revert InvalidConfiguration();
        registry = assetRegistry;
        arbitrator = disputeCouncil;
        emergencyCouncil = pauseCouncil;
        _grantRole(PAUSER_ROLE, pauseCouncil);
    }

    function createEscrow(
        bytes32 escrowId,
        bytes32 dealIdHash,
        address buyer,
        address vendor,
        address token,
        bytes32[] calldata ids,
        uint256[] calldata amounts
    ) external onlyRole(CREATOR_ROLE) whenNotPaused returns (address) {
        if (escrows[escrowId] != address(0) || registeredDeals[dealIdHash]) {
            revert AlreadyRegistered();
        }
        FinXEscrow.Terms memory terms =
            FinXEscrow.Terms(escrowId, dealIdHash, buyer, vendor, arbitrator, emergencyCouncil, token);
        FinXEscrow escrow = new FinXEscrow(terms, registry, ids, amounts);
        escrows[escrowId] = address(escrow);
        registeredDeals[dealIdHash] = true;
        emit EscrowCreated(escrowId, dealIdHash, address(escrow), buyer, vendor, token, escrow.totalAmount(), VERSION);
        return address(escrow);
    }

    function pause() external onlyRole(PAUSER_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(DEFAULT_ADMIN_ROLE) {
        _unpause();
    }
}
