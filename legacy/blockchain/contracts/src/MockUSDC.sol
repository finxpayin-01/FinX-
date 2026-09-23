// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/**
 * @title MockUSDC
 * @notice Testnet mock token for Polygon Amoy testing simulating 6-decimal USDC.
 * @dev THIS IS NOT REAL USDC. TESTNET ONLY.
 *
 * Requirements:
 * - name: "FinX Test USDC"
 * - symbol: "TEST-USDC"
 * - decimals: 6
 */
contract MockUSDC is ERC20, Ownable {
    uint8 private constant DECIMALS = 6;

    constructor(address initialOwner)
        ERC20("FinX Test USDC", "TEST-USDC")
        Ownable(initialOwner == address(0) ? msg.sender : initialOwner)
    {
        // Mint initial 1,000,000 TEST-USDC to owner for testing
        _mint(owner(), 1_000_000 * 10 ** DECIMALS);
    }

    function decimals() public pure override returns (uint8) {
        return DECIMALS;
    }

    /**
     * @notice Mint test tokens for testing purposes on Polygon Amoy.
     * @param to Recipient of test tokens.
     * @param amount Amount in base units (6 decimals).
     */
    function mint(address to, uint256 amount) external onlyOwner {
        _mint(to, amount);
    }
}
