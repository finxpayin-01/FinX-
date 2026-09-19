// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

/// @dev LOCAL TEST ONLY. Single-owner fixture, not production governance.
contract LocalCouncil {
    address public immutable owner = msg.sender;
    function execute(address target, bytes calldata data) external returns (bytes memory) {
        require(msg.sender == owner, "owner");
        (bool ok, bytes memory result) = target.call(data);
        require(ok, "execution failed");
        return result;
    }
}
