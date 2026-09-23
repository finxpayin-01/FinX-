// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {SupportedAssetRegistry} from "./SupportedAssetRegistry.sol";

interface IFactoryPause {
    function paused() external view returns (bool);
}

/// @notice Immutable, fully funded, fixed-recipient milestone escrow. Not audited.
/// @dev Direct token donations are not recognized deposits and cannot be recovered.
contract FinXEscrow is ReentrancyGuard, Pausable {
    using SafeERC20 for IERC20;

    enum Status {
        CREATED,
        AWAITING_FUNDING,
        FUNDED,
        ACTIVE,
        DISPUTED,
        COMPLETED,
        REFUNDED,
        CANCELLED
    }
    enum MilestoneStatus {
        PENDING,
        FUNDED,
        SUBMITTED,
        APPROVED,
        RELEASED,
        DISPUTED,
        REFUNDED
    }

    struct Terms {
        bytes32 escrowId;
        bytes32 dealIdHash;
        address buyer;
        address vendor;
        address arbitrator;
        address emergencyCouncil;
        address token;
    }

    struct Milestone {
        uint256 amount;
        MilestoneStatus status;
        uint256 submittedAt;
        uint256 approvedAt;
        uint256 releasedAt;
    }

    uint256 public constant MAX_MILESTONES = 64;
    bytes32 public immutable escrowId;
    bytes32 public immutable dealIdHash;
    address public immutable buyer;
    address public immutable vendor;
    address public immutable arbitrator;
    address public immutable emergencyCouncil;
    address public immutable factory;
    IERC20 public immutable token;
    SupportedAssetRegistry public immutable registry;
    uint256 public immutable totalAmount;
    uint256 public immutable createdAt;
    uint256 public fundedAmount;
    uint256 public releasedAmount;
    uint256 public refundedAmount;
    uint256 public closedMilestones;
    uint256 public disputedMilestones;
    bool public buyerAccepted;
    bool public vendorAccepted;
    bool private activity;
    Status public status;
    bytes32[] public milestoneIds;
    mapping(bytes32 => Milestone) public milestones;

    error InvalidTerms();
    error Unauthorized();
    error InvalidState();
    error AssetDisabled();
    error TransferAmountMismatch();
    error ProtocolPaused();

    event AgreementAccepted(bytes32 indexed escrowId, address indexed party);
    event EscrowFunded(bytes32 indexed escrowId, address indexed token, address indexed buyer, uint256 amount);
    event MilestoneSubmitted(bytes32 indexed escrowId, bytes32 indexed milestoneId);
    event MilestoneApproved(bytes32 indexed escrowId, bytes32 indexed milestoneId);
    event MilestoneReleased(
        bytes32 indexed escrowId, bytes32 indexed milestoneId, address indexed vendor, uint256 amount
    );
    event DisputeRaised(bytes32 indexed escrowId, bytes32 indexed milestoneId, address indexed actor);
    event DisputeResolved(bytes32 indexed escrowId, bytes32 indexed milestoneId, bool toVendor);
    event RefundIssued(bytes32 indexed escrowId, bytes32 indexed milestoneId, address indexed buyer, uint256 amount);
    event EscrowCompleted(bytes32 indexed escrowId, uint256 released, uint256 refunded);
    event EscrowCancelled(bytes32 indexed escrowId);
    event EmergencyPaused(bytes32 indexed escrowId, address indexed actor, bool isPaused);

    constructor(
        Terms memory terms,
        SupportedAssetRegistry assetRegistry,
        bytes32[] memory ids,
        uint256[] memory amounts
    ) {
        if (
            terms.escrowId == bytes32(0) || terms.dealIdHash == bytes32(0) || terms.buyer == address(0)
                || terms.vendor == address(0) || terms.buyer == terms.vendor || terms.arbitrator.code.length == 0
                || terms.emergencyCouncil.code.length == 0 || terms.arbitrator == terms.buyer
                || terms.arbitrator == terms.vendor || terms.emergencyCouncil == terms.buyer
                || terms.emergencyCouncil == terms.vendor || terms.arbitrator == terms.emergencyCouncil
                || address(assetRegistry).code.length == 0 || terms.token.code.length == 0
                || msg.sender.code.length == 0 || ids.length == 0 || ids.length > MAX_MILESTONES
                || ids.length != amounts.length
        ) revert InvalidTerms();
        escrowId = terms.escrowId;
        dealIdHash = terms.dealIdHash;
        buyer = terms.buyer;
        vendor = terms.vendor;
        arbitrator = terms.arbitrator;
        emergencyCouncil = terms.emergencyCouncil;
        token = IERC20(terms.token);
        registry = assetRegistry;
        factory = msg.sender;
        createdAt = block.timestamp;
        uint256 total;
        for (uint256 i; i < ids.length; ++i) {
            if (ids[i] == bytes32(0) || amounts[i] == 0 || milestones[ids[i]].amount != 0) revert InvalidTerms();
            total += amounts[i];
            milestones[ids[i]].amount = amounts[i];
            milestoneIds.push(ids[i]);
        }
        if (!assetRegistry.canDeposit(terms.token, total)) revert AssetDisabled();
        totalAmount = total;
    }

    modifier onlyParty() {
        if (msg.sender != buyer && msg.sender != vendor) revert Unauthorized();
        _;
    }

    modifier operational() {
        _requireNotPaused();
        if (IFactoryPause(factory).paused()) revert ProtocolPaused();
        _;
    }

    function milestoneCount() external view returns (uint256) {
        return milestoneIds.length;
    }

    function lockedAmount() external view returns (uint256) {
        return fundedAmount - releasedAmount - refundedAmount;
    }

    function acceptAgreement() external nonReentrant onlyParty operational {
        if (status != Status.CREATED) revert InvalidState();
        if (msg.sender == buyer) {
            if (buyerAccepted) revert InvalidState();
            buyerAccepted = true;
        } else {
            if (vendorAccepted) revert InvalidState();
            vendorAccepted = true;
        }
        if (buyerAccepted && vendorAccepted) status = Status.AWAITING_FUNDING;
        emit AgreementAccepted(escrowId, msg.sender);
    }

    function fundEscrow() external nonReentrant operational {
        if (msg.sender != buyer) revert Unauthorized();
        if (status != Status.AWAITING_FUNDING) revert InvalidState();
        if (!registry.canDeposit(address(token), totalAmount)) revert AssetDisabled();
        uint256 beforeBalance = token.balanceOf(address(this));
        uint256 buyerBefore = token.balanceOf(buyer);
        fundedAmount = totalAmount;
        status = Status.FUNDED;
        for (uint256 i; i < milestoneIds.length; ++i) {
            milestones[milestoneIds[i]].status = MilestoneStatus.FUNDED;
        }
        token.safeTransferFrom(buyer, address(this), totalAmount);
        if (
            token.balanceOf(address(this)) != beforeBalance + totalAmount
                || token.balanceOf(buyer) + totalAmount != buyerBefore
        ) revert TransferAmountMismatch();
        emit EscrowFunded(escrowId, address(token), buyer, totalAmount);
    }

    function submitMilestone(bytes32 id) external nonReentrant operational {
        if (msg.sender != vendor) revert Unauthorized();
        Milestone storage m = _milestone(id);
        if (m.status != MilestoneStatus.FUNDED) revert InvalidState();
        m.status = MilestoneStatus.SUBMITTED;
        m.submittedAt = block.timestamp;
        activity = true;
        _refreshStatus();
        emit MilestoneSubmitted(escrowId, id);
    }

    function approveMilestone(bytes32 id) external nonReentrant operational {
        if (msg.sender != buyer) revert Unauthorized();
        Milestone storage m = _milestone(id);
        if (m.status != MilestoneStatus.SUBMITTED) revert InvalidState();
        m.status = MilestoneStatus.APPROVED;
        m.approvedAt = block.timestamp;
        emit MilestoneApproved(escrowId, id);
    }

    function releaseMilestone(bytes32 id) external nonReentrant onlyParty operational {
        if (disputedMilestones != 0) revert InvalidState();
        Milestone storage m = _milestone(id);
        if (m.status != MilestoneStatus.APPROVED) revert InvalidState();
        _settle(id, m, true);
    }

    function raiseDispute(bytes32 id) external nonReentrant onlyParty {
        Milestone storage m = _milestone(id);
        if (!_unsettled(m.status)) revert InvalidState();
        m.status = MilestoneStatus.DISPUTED;
        ++disputedMilestones;
        activity = true;
        _refreshStatus();
        emit DisputeRaised(escrowId, id, msg.sender);
    }

    function resolveDispute(bytes32 id, bool toVendor) external nonReentrant operational {
        if (msg.sender != arbitrator) revert Unauthorized();
        Milestone storage m = _milestone(id);
        if (m.status != MilestoneStatus.DISPUTED) revert InvalidState();
        --disputedMilestones;
        _settle(id, m, toVendor);
        emit DisputeResolved(escrowId, id, toVendor);
    }

    function refundMilestone(bytes32 id) external nonReentrant operational {
        if (msg.sender != vendor) revert Unauthorized();
        Milestone storage m = _milestone(id);
        if (!_unsettled(m.status)) revert InvalidState();
        _settle(id, m, false);
    }

    function cancelEscrow() external nonReentrant onlyParty {
        if (fundedAmount != 0 || (status != Status.CREATED && status != Status.AWAITING_FUNDING)) {
            revert InvalidState();
        }
        status = Status.CANCELLED;
        emit EscrowCancelled(escrowId);
    }

    function pause() external nonReentrant {
        if (msg.sender != emergencyCouncil) revert Unauthorized();
        _pause();
        emit EmergencyPaused(escrowId, msg.sender, true);
    }

    function unpause() external nonReentrant {
        if (msg.sender != emergencyCouncil) revert Unauthorized();
        _unpause();
        emit EmergencyPaused(escrowId, msg.sender, false);
    }

    function _milestone(bytes32 id) private view returns (Milestone storage m) {
        m = milestones[id];
        if (m.amount == 0) revert InvalidTerms();
    }

    function _unsettled(MilestoneStatus s) private pure returns (bool) {
        return s == MilestoneStatus.FUNDED || s == MilestoneStatus.SUBMITTED || s == MilestoneStatus.APPROVED;
    }

    function _settle(bytes32 id, Milestone storage m, bool toVendor) private {
        if (!registry.canWithdraw(address(token))) revert AssetDisabled();
        uint256 amount = m.amount;
        address recipient = toVendor ? vendor : buyer;
        m.status = toVendor ? MilestoneStatus.RELEASED : MilestoneStatus.REFUNDED;
        if (toVendor) {
            releasedAmount += amount;
            m.releasedAt = block.timestamp;
        } else {
            refundedAmount += amount;
        }
        ++closedMilestones;
        activity = true;
        _refreshStatus();
        uint256 beforeBalance = token.balanceOf(address(this));
        uint256 recipientBefore = token.balanceOf(recipient);
        token.safeTransfer(recipient, amount);
        if (
            token.balanceOf(address(this)) + amount != beforeBalance
                || token.balanceOf(recipient) != recipientBefore + amount
        ) revert TransferAmountMismatch();
        if (toVendor) emit MilestoneReleased(escrowId, id, vendor, amount);
        else emit RefundIssued(escrowId, id, buyer, amount);
        if (closedMilestones == milestoneIds.length) emit EscrowCompleted(escrowId, releasedAmount, refundedAmount);
    }

    function _refreshStatus() private {
        if (closedMilestones == milestoneIds.length) status = releasedAmount == 0 ? Status.REFUNDED : Status.COMPLETED;
        else if (disputedMilestones != 0) status = Status.DISPUTED;
        else status = activity ? Status.ACTIVE : Status.FUNDED;
    }
}
