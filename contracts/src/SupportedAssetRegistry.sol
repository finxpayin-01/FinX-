// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {
    AccessControlDefaultAdminRules
} from "@openzeppelin/contracts/access/extensions/AccessControlDefaultAdminRules.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";

/// @notice Chain-local allowlist. No asset is approved at deployment.
contract SupportedAssetRegistry is AccessControlDefaultAdminRules {
    struct Asset {
        uint8 decimals;
        bool enabled;
        bool depositEnabled;
        bool withdrawalEnabled;
        uint256 minimumAmount;
        uint256 maximumAmount;
    }

    mapping(address => Asset) public assets;
    error InvalidAsset();
    event AssetConfigured(
        address indexed token,
        uint8 decimals,
        bool enabled,
        bool depositEnabled,
        bool withdrawalEnabled,
        uint256 minimumAmount,
        uint256 maximumAmount
    );

    constructor(address governance, uint48 adminDelay) AccessControlDefaultAdminRules(adminDelay, governance) {
        if (governance.code.length == 0 || adminDelay == 0) revert InvalidAsset();
    }

    function configureAsset(address token, Asset calldata asset) external onlyRole(DEFAULT_ADMIN_ROLE) {
        if (
            token.code.length == 0 || asset.minimumAmount == 0 || asset.maximumAmount < asset.minimumAmount
                || IERC20Metadata(token).decimals() != asset.decimals
        ) revert InvalidAsset();
        assets[token] = asset;
        emit AssetConfigured(
            token,
            asset.decimals,
            asset.enabled,
            asset.depositEnabled,
            asset.withdrawalEnabled,
            asset.minimumAmount,
            asset.maximumAmount
        );
    }

    function canDeposit(address token, uint256 amount) external view returns (bool) {
        Asset memory a = assets[token];
        return a.enabled && a.depositEnabled && amount >= a.minimumAmount && amount <= a.maximumAmount;
    }

    function canWithdraw(address token) external view returns (bool) {
        Asset memory a = assets[token];
        return a.enabled && a.withdrawalEnabled;
    }
}
