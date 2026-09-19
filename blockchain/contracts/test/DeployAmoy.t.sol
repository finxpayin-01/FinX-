// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {DeployAmoy} from "../script/DeployAmoy.s.sol";
import {TestnetCouncil} from "../src/TestnetCouncil.sol";
import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {MockUSDC} from "../src/MockUSDC.sol";

interface Vm {
    function startBroadcast() external;
    function stopBroadcast() external;
    function envOr(string calldata, address) external returns (address);
    function envOr(string calldata, uint256) external returns (uint256);
    function toString(address) external pure returns (string memory);
    function prank(address) external;
}

contract DeployAmoyTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    DeployAmoy internal deployer;

    function setUp() public {
        deployer = new DeployAmoy();
    }

    function testDeploymentAndGovernanceSetup() public {
        deployer.run();

        TestnetCouncil governance = deployer.governance();
        TestnetCouncil disputeCouncil = deployer.disputeCouncil();
        TestnetCouncil pauseCouncil = deployer.pauseCouncil();
        SupportedAssetRegistry registry = deployer.registry();
        FinXEscrowFactory factory = deployer.factory();
        address token = deployer.testToken();

        // 1. Verify code lengths
        require(address(governance).code.length > 0, "governance has no code");
        require(address(disputeCouncil).code.length > 0, "disputeCouncil has no code");
        require(address(pauseCouncil).code.length > 0, "pauseCouncil has no code");
        require(address(registry).code.length > 0, "registry has no code");
        require(address(factory).code.length > 0, "factory has no code");
        require(token.code.length > 0, "token has no code");

        // 2. Verify all councils are distinct
        require(address(governance) != address(disputeCouncil), "governance == disputeCouncil");
        require(address(governance) != address(pauseCouncil), "governance == pauseCouncil");
        require(address(disputeCouncil) != address(pauseCouncil), "disputeCouncil == pauseCouncil");

        // 3. Verify relationships
        require(address(factory.registry()) == address(registry), "registry mismatch");
        require(factory.arbitrator() == address(disputeCouncil), "arbitrator mismatch");
        require(factory.emergencyCouncil() == address(pauseCouncil), "emergencyCouncil mismatch");

        // 4. Verify roles
        bytes32 creatorRole = factory.CREATOR_ROLE();
        require(factory.hasRole(creatorRole, governance.owner()), "creator role not granted");

        bytes32 pauserRole = factory.PAUSER_ROLE();
        require(factory.hasRole(pauserRole, address(pauseCouncil)), "pauser role not granted");

        // 5. Verify asset registry canDeposit and canWithdraw
        require(registry.canDeposit(token, 1_000_000), "canDeposit min failed");
        require(registry.canDeposit(token, 100_000_000), "canDeposit mid failed");
        require(registry.canDeposit(token, 1_000_000_000_000), "canDeposit max failed");
        require(!registry.canDeposit(token, 999_999), "canDeposit below min succeeded");
        require(!registry.canDeposit(token, 1_000_000_000_001), "canDeposit above max succeeded");
        require(registry.canWithdraw(token), "canWithdraw failed");
    }

    function testCouncilExecutionAuthorization() public {
        deployer.run();

        TestnetCouncil governance = deployer.governance();
        SupportedAssetRegistry registry = deployer.registry();
        address token = deployer.testToken();

        // Verify only owner of TestnetCouncil can execute
        address owner = governance.owner();

        SupportedAssetRegistry.Asset memory updateAsset = SupportedAssetRegistry.Asset({
            decimals: 6,
            enabled: true,
            depositEnabled: false, // disable deposits
            withdrawalEnabled: true,
            minimumAmount: 1_000_000,
            maximumAmount: 1_000_000_000_000
        });

        // Prank as the council owner
        vm.prank(owner);
        governance.execute(
            address(registry),
            abi.encodeCall(registry.configureAsset, (token, updateAsset))
        );

        require(!registry.canDeposit(token, 10_000_000), "deposit should now be disabled");
        require(registry.canWithdraw(token), "withdraw should still be enabled");
    }
}
