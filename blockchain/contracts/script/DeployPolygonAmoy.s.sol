// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {SupportedAssetRegistry} from "../src/SupportedAssetRegistry.sol";
import {FinXEscrowFactory} from "../src/FinXEscrowFactory.sol";
import {LocalCouncil} from "../test/LocalCouncil.sol";

interface Vm {
    function startBroadcast(uint256 privateKey) external;
    function stopBroadcast() external;
    function envOr(string calldata name, uint256 defaultValue) external returns (uint256);
    function addr(uint256 privateKey) external returns (address);
}

contract Empty {}

contract DeployPolygonAmoy {
    Vm internal constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));

    event LogAddress(string name, address addr);

    function run() public {
        uint256 pk = vm.envOr("PRIVATE_KEY", uint256(0));
        require(pk != 0, "Missing PRIVATE_KEY environment variable");
        require(block.chainid == 80002, "Must be Amoy (80002)");

        address deployer = vm.addr(pk);
        emit LogAddress("Deployer", deployer);

        vm.startBroadcast(pk);
        
        LocalCouncil gov = new LocalCouncil();
        Empty arbitrator = new Empty();
        Empty emergency = new Empty();

        address usdc = 0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582;

        SupportedAssetRegistry registry = new SupportedAssetRegistry(address(gov), 1 days);

        FinXEscrowFactory factory = new FinXEscrowFactory(
            address(gov),
            1 days,
            registry,
            address(arbitrator),
            address(emergency)
        );

        bytes memory configureData = abi.encodeWithSelector(
            SupportedAssetRegistry.configureAsset.selector,
            usdc,
            SupportedAssetRegistry.Asset(6, true, true, true, 1, type(uint256).max)
        );
        gov.execute(address(registry), configureData);
        
        gov.execute(
            address(factory), 
            abi.encodeWithSelector(factory.grantRole.selector, factory.CREATOR_ROLE(), deployer)
        );

        vm.stopBroadcast();
        
        emit LogAddress("Registry address:", address(registry));
        emit LogAddress("Factory address:", address(factory));
    }
}
