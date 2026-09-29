package com.finx.verification.provider.mock;

import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.provider.model.VerificationProviderModels.*;
import com.finx.verification.service.NameMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

@Component("mockVerificationProvider")
public class MockVerificationProvider implements VerificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockVerificationProvider.class);

    private static final Pattern PAN_REGEX = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final Pattern GST_REGEX = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    private final NameMatchService nameMatchService;

    public MockVerificationProvider(NameMatchService nameMatchService) {
        this.nameMatchService = nameMatchService;
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public boolean isSandbox() {
        return true;
    }

    @Override
    public PanVerificationResult verifyPan(PanVerificationRequest request) {
        String pan = request.pan() != null ? request.pan().trim().toUpperCase() : "";
        log.info("[MOCK-SANDBOX] Verifying PAN: {}", mask(pan));

        if (pan.length() != 10 || !PAN_REGEX.matcher(pan).matches() || pan.endsWith("0000") || pan.startsWith("FAIL")) {
            return new PanVerificationResult(
                    false,
                    pan,
                    null,
                    "INVALID",
                    null,
                    0.0,
                    "MISMATCH",
                    "MOCK-SANDBOX-PAN-FAIL-" + UUID.randomUUID().toString().substring(0, 8),
                    "PAN is invalid or not found in NSDL/ITD records [MOCK SANDBOX]"
            );
        }

        char fourthChar = pan.charAt(3);
        String category = switch (fourthChar) {
            case 'C' -> "COMPANY";
            case 'P', 'D', 'E' -> "INDIVIDUAL";
            case 'F' -> "PARTNERSHIP";
            case 'H' -> "HUF";
            case 'T' -> "TRUST";
            default -> "OTHER";
        };

        String registeredName;
        if (pan.contains("REVW")) {
            registeredName = (request.fullName() != null ? request.fullName() : "FinX User") + " Old Alias";
        } else {
            registeredName = request.fullName() != null && !request.fullName().isBlank()
                    ? request.fullName()
                    : "Verified Indian Entity";
        }

        var matchEval = nameMatchService.compareNames(request.fullName(), registeredName);

        return new PanVerificationResult(
                true,
                pan,
                registeredName,
                "VALID",
                category,
                matchEval.score(),
                matchEval.result().name(),
                "MOCK-SANDBOX-PAN-" + UUID.randomUUID().toString().substring(0, 8),
                "PAN successfully verified against simulated sandbox database"
        );
    }

    @Override
    public GstVerificationResult verifyGstin(GstVerificationRequest request) {
        String gstin = request.gstin() != null ? request.gstin().trim().toUpperCase() : "";
        log.info("[MOCK-SANDBOX] Verifying GSTIN: {}", mask(gstin));

        if (gstin.length() != 15 || !GST_REGEX.matcher(gstin).matches() || gstin.endsWith("0000") || gstin.startsWith("FAIL")) {
            return new GstVerificationResult(
                    false,
                    gstin,
                    null,
                    null,
                    "INACTIVE",
                    null,
                    null,
                    null,
                    0.0,
                    "MISMATCH",
                    "MOCK-SANDBOX-GST-FAIL-" + UUID.randomUUID().toString().substring(0, 8),
                    "GSTIN record inactive or not found in GSTN database [MOCK SANDBOX]"
            );
        }

        String legalName = (request.legalName() != null && !request.legalName().isBlank())
                ? request.legalName()
                : "FinX Corporate Enterprise Private Limited";

        if (gstin.contains("REVW")) {
            legalName = legalName + " Holdings Branch";
        }

        var matchEval = nameMatchService.compareNames(request.legalName(), legalName);

        return new GstVerificationResult(
                true,
                gstin,
                legalName,
                request.legalName() + " Trade Division",
                "ACTIVE",
                LocalDate.of(2020, 1, 15),
                "Private Limited Company",
                "Plot 42, Bandra Kurla Complex, Bandra East, Mumbai, Maharashtra 400051",
                matchEval.score(),
                matchEval.result().name(),
                "MOCK-SANDBOX-GST-" + UUID.randomUUID().toString().substring(0, 8),
                "GSTIN active and verified in mock sandbox"
        );
    }

    @Override
    public CinVerificationResult verifyCin(CinVerificationRequest request) {
        String cin = request.cinOrLlpin() != null ? request.cinOrLlpin().trim().toUpperCase() : "";
        log.info("[MOCK-SANDBOX] Verifying CIN/LLPIN: {}", mask(cin));

        if (cin.length() < 7 || cin.endsWith("0000") || cin.startsWith("FAIL")) {
            return new CinVerificationResult(
                    false,
                    cin,
                    null,
                    "INACTIVE",
                    null,
                    null,
                    null,
                    0.0,
                    "MISMATCH",
                    "MOCK-SANDBOX-CIN-FAIL-" + UUID.randomUUID().toString().substring(0, 8),
                    "CIN/LLPIN not found in Ministry of Corporate Affairs (MCA) records [MOCK SANDBOX]"
            );
        }

        String companyName = request.companyName() != null && !request.companyName().isBlank()
                ? request.companyName()
                : "FinX Corporate Technologies Private Limited";

        var matchEval = nameMatchService.compareNames(request.companyName(), companyName);

        return new CinVerificationResult(
                true,
                cin,
                companyName,
                "ACTIVE",
                LocalDate.of(2018, 6, 20),
                "Company limited by Shares / Non-govt company",
                "Floor 12, Maker Chambers IV, Nariman Point, Mumbai 400021",
                matchEval.score(),
                matchEval.result().name(),
                "MOCK-SANDBOX-CIN-" + UUID.randomUUID().toString().substring(0, 8),
                "Corporate registration verified in simulated MCA registry"
        );
    }

    @Override
    public BankVerificationResult verifyBankAccount(BankVerificationRequest request) {
        String acc = request.accountNumber() != null ? request.accountNumber().trim() : "";
        String ifsc = request.ifsc() != null ? request.ifsc().trim().toUpperCase() : "";
        log.info("[MOCK-SANDBOX] Verifying Bank Account: IFSC={}, Acc={}", ifsc, mask(acc));

        if (acc.length() < 9 || acc.endsWith("0000") || acc.startsWith("FAIL") || ifsc.length() != 11) {
            return new BankVerificationResult(
                    false,
                    mask(acc),
                    ifsc,
                    null,
                    "State Bank of India",
                    "Mumbai Main Branch",
                    0.0,
                    "MISMATCH",
                    "MOCK-SANDBOX-BANK-FAIL-" + UUID.randomUUID().toString().substring(0, 8),
                    "Penny drop verification failed: account inactive or invalid IFSC [MOCK SANDBOX]"
            );
        }

        String registeredName;
        if (acc.endsWith("9999") || acc.contains("MISMATCH")) {
            // Simulate bank account name mismatch scenario
            registeredName = "Completely Different Unrelated Third Party Person";
        } else if (request.accountHolderName() != null && !request.accountHolderName().isBlank()) {
            registeredName = request.accountHolderName();
        } else {
            registeredName = "FinX Authorized Account Holder";
        }

        var matchEval = nameMatchService.compareNames(request.accountHolderName(), registeredName);

        return new BankVerificationResult(
                true,
                mask(acc),
                ifsc,
                registeredName,
                "HDFC Bank Ltd",
                "Nariman Point Branch",
                matchEval.score(),
                matchEval.result().name(),
                "MOCK-SANDBOX-BANK-" + UUID.randomUUID().toString().substring(0, 8),
                "Penny drop simulated successfully: Re. 1.00 credit confirmed with beneficiary bank"
        );
    }

    @Override
    public IdentitySessionResult createIdentitySession(IdentitySessionRequest request) {
        String ref = "MOCK-SANDBOX-ID-SESS-" + UUID.randomUUID().toString().substring(0, 8);
        return new IdentitySessionResult(
                ref,
                "https://mock.sandbox.finx.com/identity/session/" + ref,
                ref,
                "SESSION_CREATED"
        );
    }

    @Override
    public IdentityStatusResult getIdentityStatus(String sessionId) {
        if (sessionId != null && sessionId.contains("FAIL")) {
            return new IdentityStatusResult(
                    sessionId,
                    "FAILED",
                    null,
                    null,
                    null,
                    null,
                    0.0,
                    sessionId,
                    "DigiLocker / eKYC identity verification rejected or cancelled by user [MOCK SANDBOX]"
            );
        }

        return new IdentityStatusResult(
                sessionId,
                "VERIFIED",
                "Rajesh Kumar Sharma",
                "XXXX-XXXX-7654",
                "1989-11-23",
                "B-402, Green Meadows, Sector 14, Navi Mumbai 400614",
                95.0,
                sessionId,
                "Aadhaar eKYC via DigiLocker verified in mock sandbox"
        );
    }

    private String mask(String str) {
        if (str == null || str.length() <= 4) return "****";
        return "*".repeat(str.length() - 4) + str.substring(str.length() - 4);
    }
}
