package com.finx.release.observation.service;

import com.finx.release.observation.entity.SettlementObservation;

import java.util.UUID;

public record SettlementRecoveryResult(
        UUID attemptId,
        boolean recovered,
        String recoveryAction,
        SettlementObservation observation
) {}
