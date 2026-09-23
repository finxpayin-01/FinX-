// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {TestnetCouncil} from "../src/TestnetCouncil.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {MockUSDC} from "../src/MockUSDC.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";

/**
 * @title DeployAmoy
 * @notice End-to-end deployment script for Polygon Amoy Testnet (Chain ID 80002).
 * @dev Deploys dependencies in strict order, configures governance and permissions,
 * registers TEST-USDC, and validates all security invariants.
 */
contract DeployAmoy is Script {
    uint256 public constant POLYGON_AMOY_CHAIN_ID = 80002;

    TestnetCouncil public governance;
    TestnetCouncil public disputeCouncil;
    TestnetCouncil public pauseCouncil;
    SupportedAssetRegistry public registry;
    FinXEscrowFactory public factory;
    address public testToken;

    function run() external {
        // Enforce network check if running on a live network
        if (block.chainid != 31337 && block.chainid != POLYGON_AMOY_CHAIN_ID) {
            console.log("WARNING: Deploying on chain ID:", block.chainid);
        }

        address deployer = block.chainid == 31337 ? msg.sender : vm.envOr("DEPLOYER_ADDRESS", msg.sender);
        address intendedCreator = block.chainid == 31337 ? deployer : vm.envOr("BLOCKCHAIN_CREATOR_ADDRESS", deployer);
        uint48 adminDelay = uint48(vm.envOr("GOVERNANCE_ADMIN_DELAY_SECONDS", uint256(86400)));
        address configuredToken = vm.envOr("USDC_CONTRACT_ADDRESS", address(0));

        console.log("=== FINX POLYGON AMOY DEPLOYMENT START ===");
        console.log("Deployer / Council Owner:", deployer);
        console.log("Intended Creator:", intendedCreator);
        console.log("Admin Delay (s):", adminDelay);

        vm.startBroadcast(deployer);

        // 1. Governance contract (owner set explicitly to deployer)
        governance = new TestnetCouncil("Governance", deployer);

        // 2. DisputeCouncil contract
        disputeCouncil = new TestnetCouncil("DisputeCouncil", deployer);

        // 3. PauseCouncil contract
        pauseCouncil = new TestnetCouncil("PauseCouncil", deployer);

        // 4. SupportedAssetRegistry
        registry = new SupportedAssetRegistry(address(governance), adminDelay);

        // 5. FinXEscrowFactory
        factory = new FinXEscrowFactory(
            address(governance),
            adminDelay,
            registry,
            address(disputeCouncil),
            address(pauseCouncil)
        );

        // 6. Test Token (MockUSDC if not configured or empty)
        if (configuredToken != address(0) && configuredToken.code.length > 0) {
            testToken = configuredToken;
            console.log("Using existing configured token at:", testToken);
        } else {
            MockUSDC mock = new MockUSDC(address(0));
            testToken = address(mock);
            // Mint test balance to creator if different from deployer
            if (intendedCreator != deployer) {
                mock.mint(intendedCreator, 100_000 * 10 ** 6);
            }
            console.log("Deployed MockUSDC at:", testToken);
        }

        // 7. Configure asset in SupportedAssetRegistry via governance path
        SupportedAssetRegistry.Asset memory assetConfig = SupportedAssetRegistry.Asset({
            decimals: 6,
            enabled: true,
            depositEnabled: true,
            withdrawalEnabled: true,
            minimumAmount: 1_000_000, // 1 TEST-USDC (6 decimals)
            maximumAmount: 1_000_000_000_000 // 1,000,000 TEST-USDC
        });

        governance.execute(
            address(registry),
            abi.encodeCall(registry.configureAsset, (testToken, assetConfig))
        );

        // 8. Grant CREATOR_ROLE to intended creator via governance path
        governance.execute(
            address(factory),
            abi.encodeCall(factory.grantRole, (factory.CREATOR_ROLE(), intendedCreator))
        );

        vm.stopBroadcast();

        // ==========================================
        // Verification of Invariants
        // ==========================================
        require(address(governance).code.length > 0, "governance.code.length == 0");
        require(address(disputeCouncil).code.length > 0, "disputeCouncil.code.length == 0");
        require(address(pauseCouncil).code.length > 0, "pauseCouncil.code.length == 0");
        require(address(registry).code.length > 0, "registry.code.length == 0");
        require(address(factory).code.length > 0, "factory.code.length == 0");
        require(testToken.code.length > 0, "testToken.code.length == 0");

        require(address(governance) != address(disputeCouncil), "governance == disputeCouncil");
        require(address(governance) != address(pauseCouncil), "governance == pauseCouncil");
        require(address(disputeCouncil) != address(pauseCouncil), "disputeCouncil == pauseCouncil");

        require(address(factory.registry()) == address(registry), "factory.registry mismatch");
        require(factory.arbitrator() == address(disputeCouncil), "factory.arbitrator mismatch");
        require(factory.emergencyCouncil() == address(pauseCouncil), "factory.emergencyCouncil mismatch");

        require(factory.hasRole(factory.CREATOR_ROLE(), intendedCreator), "factory CREATOR_ROLE not granted");
        require(registry.canDeposit(testToken, 10_000_000), "registry canDeposit check failed");
        require(registry.canWithdraw(testToken), "registry canWithdraw check failed");

        console.log("=== ALL INVARIANTS VERIFIED SUCCESSFULLY ===");

        // Phase 7: Deployment Output
        console.log("------------------------------------------");
        console.log("CHAIN_ID=80002");
        console.log(string.concat("GOVERNANCE_ADDRESS=", vm.toString(address(governance))));
        console.log(string.concat("DISPUTE_COUNCIL_ADDRESS=", vm.toString(address(disputeCouncil))));
        console.log(string.concat("PAUSE_COUNCIL_ADDRESS=", vm.toString(address(pauseCouncil))));
        console.log(string.concat("SUPPORTED_ASSET_REGISTRY_ADDRESS=", vm.toString(address(registry))));
        console.log(string.concat("ESCROW_FACTORY_ADDRESS=", vm.toString(address(factory))));
        console.log(string.concat("TEST_USDC_ADDRESS=", vm.toString(testToken)));
        console.log(string.concat("CREATOR_ADDRESS=", vm.toString(intendedCreator)));
        console.log("------------------------------------------");
    }
}
