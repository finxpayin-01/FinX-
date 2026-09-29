package com.finx.verification.provider;

import com.finx.verification.provider.model.VerificationProviderModels.*;

public interface VerificationProvider {

    String getProviderName();

    boolean isSandbox();

    PanVerificationResult verifyPan(PanVerificationRequest request);

    GstVerificationResult verifyGstin(GstVerificationRequest request);

    CinVerificationResult verifyCin(CinVerificationRequest request);

    BankVerificationResult verifyBankAccount(BankVerificationRequest request);

    IdentitySessionResult createIdentitySession(IdentitySessionRequest request);

    IdentityStatusResult getIdentityStatus(String sessionId);
}
