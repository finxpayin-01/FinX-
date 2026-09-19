// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/**
 * @title TestnetCouncil
 * @notice Functional testnet governance and council execution contract for Polygon Amoy.
 * @dev THIS IS NOT PRODUCTION READY.
 *
 * Production requirements:
 * - Production governance MUST use audited multi-signature contracts (e.g. Safe multisig with threshold >= 3/5)
 *   and/or TimelockController with community oversight.
 * - This single-owner testnet council exists solely to satisfy FinX contract security invariants
 *   (governance.code.length > 0, disputeCouncil.code.length > 0, pauseCouncil.code.length > 0,
 *   and address distinctness) while allowing automated test execution on Polygon Amoy.
 */
contract TestnetCouncil is Ownable {
    string public councilRole;

    event CallExecuted(address indexed target, uint256 value, bytes data);

    error ExecutionFailed(bytes returnData);

    constructor(string memory _councilRole, address initialOwner)
        Ownable(initialOwner == address(0) ? msg.sender : initialOwner)
    {
        councilRole = _councilRole;
    }

    /**
     * @notice Executes an arbitrary transaction on behalf of this council.
     * @param target The target contract address (e.g. factory, registry, or escrow).
     * @param data The calldata to forward.
     * @return The result data of the execution.
     */
    function execute(address target, bytes calldata data) external payable onlyOwner returns (bytes memory) {
        (bool success, bytes memory result) = target.call{value: msg.value}(data);
        if (!success) {
            revert ExecutionFailed(result);
        }
        emit CallExecuted(target, msg.value, data);
        return result;
    }

    receive() external payable {}
}
