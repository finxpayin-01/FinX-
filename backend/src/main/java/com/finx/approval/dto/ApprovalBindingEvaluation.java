package com.finx.approval.dto;

import com.finx.approval.entity.MilestoneApprovalBinding;

public record ApprovalBindingEvaluation(
        boolean present,
        boolean current,
        String reasonCode,
        MilestoneApprovalBinding binding
) {}
