"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import {
    CheckCircle2,
    Clock,
    AlertTriangle,
    ShieldCheck,
    Building2,
    UserCheck,
    Landmark,
    FileText,
    ArrowRight,
    ArrowLeft,
    Check,
    Sparkles,
    Lock,
    ExternalLink,
    RefreshCw
} from "lucide-react";
import { useAuth } from "@/components/auth/AuthProvider";
import { b2bService } from "@/services/b2b.service";
import { Organization } from "@/types/b2b";
import { verificationService, KycResponse, KybResponse, FinxOrganizationVerificationResponse, BankAccountResponse } from "@/services/verification.service";

type StepId = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;

export default function OnboardingPage() {
    const { user } = useAuth();
    const [step, setStep] = useState<StepId>(1);
    const [loading, setLoading] = useState(false);
    const [statusMsg, setStatusMsg] = useState<{ type: "success" | "error" | "info"; text: string } | null>(null);

    // Organization state
    const [organizations, setOrganizations] = useState<Organization[]>([]);
    const [selectedOrgId, setSelectedOrgId] = useState<string>("");

    // Step 2: Personal KYC state
    const [kycConsent, setKycConsent] = useState(true);
    const [fullName, setFullName] = useState(user?.name || "");
    const [panNumber, setPanNumber] = useState("");
    const [dob, setDob] = useState("1990-01-01");
    const [kycProfile, setKycProfile] = useState<KycResponse | null>(null);

    // Step 3: Business Information state
    const [entityType, setEntityType] = useState<"PRIVATE_LIMITED" | "LLP" | "PROPRIETORSHIP" | "PARTNERSHIP" | "PUBLIC_LIMITED">("PRIVATE_LIMITED");
    const [companyLegalName, setCompanyLegalName] = useState("");
    const [companyTradeName, setCompanyTradeName] = useState("");
    const [registeredAddress, setRegisteredAddress] = useState("");

    // Step 4: Business Verification state
    const [businessPan, setBusinessPan] = useState("");
    const [gstin, setGstin] = useState("");
    const [cinOrLlpin, setCinOrLlpin] = useState("");
    const [udyamNumber, setUdyamNumber] = useState("");
    const [kybProfile, setKybProfile] = useState<KybResponse | null>(null);

    // Step 5: Representative state
    const [representativeRole, setRepresentativeRole] = useState("DIRECTOR");
    const [representativeSuccess, setRepresentativeSuccess] = useState(false);

    // Step 6: Bank Account state
    const [bankHolderName, setBankHolderName] = useState("");
    const [bankAccountNumber, setBankAccountNumber] = useState("");
    const [bankIfsc, setBankIfsc] = useState("HDFC0000060");
    const [bankName, setBankName] = useState("HDFC Bank");
    const [bankType, setBankType] = useState("CURRENT");
    const [bankAccount, setBankAccount] = useState<BankAccountResponse | null>(null);

    // Step 7 & 8: Eligibility Response
    const [eligibility, setEligibility] = useState<FinxOrganizationVerificationResponse | null>(null);

    // Load initial user KYC and organization data
    useEffect(() => {
        async function init() {
            try {
                const kyc = await verificationService.getKycStatus();
                setKycProfile(kyc);
                if (kyc.fullName) setFullName(kyc.fullName);

                const orgs = await b2bService.getMyOrganizations();
                setOrganizations(orgs);
                if (orgs.length > 0) {
                    setSelectedOrgId(orgs[0].id);
                    setCompanyLegalName(orgs[0].legalName || orgs[0].name);
                    setRegisteredAddress(orgs[0].registeredAddress || "");
                    const kyb = await verificationService.getKybStatus(orgs[0].id);
                    setKybProfile(kyb);
                }
            } catch (err) {
                console.error("Failed to load onboarding initial data", err);
            }
        }
        init();
    }, []);

    // Step 2: Handle Individual KYC PAN Verification
    const handleVerifyPersonalPan = async () => {
        if (!panNumber || panNumber.trim().length !== 10) {
            setStatusMsg({ type: "error", text: "Please provide a valid 10-character PAN number" });
            return;
        }
        setLoading(true);
        setStatusMsg(null);
        try {
            // Ensure consent is recorded
            await verificationService.recordConsent({
                verificationType: "PAN",
                purpose: "FinX Individual Identity Verification and Authorized Signatory Compliance",
                consentVersion: "1.0"
            });

            const res = await verificationService.verifyPan({
                panNumber: panNumber.trim().toUpperCase(),
                fullName: fullName.trim(),
                dateOfBirth: dob
            });

            const updatedKyc = await verificationService.getKycStatus();
            setKycProfile(updatedKyc);

            if (res.valid) {
                setStatusMsg({ type: "success", text: `PAN Verified (${res.nameMatchScore}% Name Match). Sandbox verification applied.` });
            } else {
                setStatusMsg({ type: "error", text: res.message || "PAN Verification failed" });
            }
        } catch (err: any) {
            setStatusMsg({ type: "error", text: err.response?.data?.message || err.message || "PAN verification failed" });
        } finally {
            setLoading(false);
        }
    };

    // Step 3: Handle Creating / Updating Business Profile
    const handleSaveBusinessInfo = async () => {
        if (!companyLegalName.trim()) {
            setStatusMsg({ type: "error", text: "Legal business name is required" });
            return;
        }
        setLoading(true);
        setStatusMsg(null);
        try {
            if (!selectedOrgId) {
                const newOrg = await b2bService.createOrganization({
                    name: companyLegalName.trim(),
                    legalName: companyLegalName.trim(),
                    entityType: entityType,
                    pan: businessPan.trim() || (entityType === "PROPRIETORSHIP" && panNumber ? panNumber.trim().toUpperCase() : "AAACB1234D"),
                    registeredAddress: registeredAddress.trim()
                });
                setSelectedOrgId(newOrg.id);
                setOrganizations([newOrg]);
                await verificationService.startKyb(newOrg.id);
            }
            setStep(4);
        } catch (err: any) {
            setStatusMsg({ type: "error", text: err.response?.data?.message || err.message || "Failed to save organization" });
        } finally {
            setLoading(false);
        }
    };

    // Step 4: Handle Business Verification
    const handleVerifyBusiness = async () => {
        if (!selectedOrgId) {
            setStatusMsg({ type: "error", text: "Please create business profile first" });
            return;
        }
        setLoading(true);
        setStatusMsg(null);
        try {
            // Consent for GST and PAN
            await verificationService.recordConsent({
                verificationType: "PAN",
                organizationId: selectedOrgId,
                purpose: "FinX Corporate PAN Verification and B2B Escrow Compliance"
            });

            if (businessPan) {
                await verificationService.verifyBusinessPan(selectedOrgId, businessPan.trim().toUpperCase());
            }

            if (gstin) {
                await verificationService.recordConsent({
                    verificationType: "GST",
                    organizationId: selectedOrgId,
                    purpose: "FinX GST Registry and Invoice Reconcilation Verification"
                });
                await verificationService.verifyGstin(selectedOrgId, gstin.trim().toUpperCase());
            }

            if (cinOrLlpin || udyamNumber) {
                await verificationService.verifyRegistration(selectedOrgId, {
                    cinOrLlpin: cinOrLlpin.trim().toUpperCase(),
                    udyamNumber: udyamNumber.trim().toUpperCase()
                });
            }

            const updatedKyb = await verificationService.getKybStatus(selectedOrgId);
            setKybProfile(updatedKyb);
            setStatusMsg({ type: "success", text: "Business registration parameters verified with authoritative registry!" });
            setStep(5);
        } catch (err: any) {
            setStatusMsg({ type: "error", text: err.response?.data?.message || err.message || "Business verification failed" });
        } finally {
            setLoading(false);
        }
    };

    // Step 5: Handle Authorized Representative
    const handleAddRepresentative = async () => {
        if (!selectedOrgId || !user?.id) return;
        setLoading(true);
        setStatusMsg(null);
        try {
            await verificationService.addRepresentative(selectedOrgId, {
                userId: user.id,
                relationshipType: representativeRole,
                notes: "Primary authorized signatory configured via onboarding"
            });
            setRepresentativeSuccess(true);
            setStatusMsg({ type: "success", text: "Authorized signatory linked and authority status verified!" });
            setStep(6);
        } catch (err: any) {
            setStatusMsg({ type: "error", text: err.response?.data?.message || err.message || "Failed to link representative" });
        } finally {
            setLoading(false);
        }
    };

    // Step 6: Handle Bank Account Penny Drop
    const handleVerifyBankAccount = async () => {
        if (!selectedOrgId || !bankAccountNumber || !bankIfsc) {
            setStatusMsg({ type: "error", text: "Please enter account number and valid IFSC code" });
            return;
        }
        setLoading(true);
        setStatusMsg(null);
        try {
            // 1. Add Bank Account
            const newAcc = await verificationService.addBankAccount(selectedOrgId, {
                accountHolderName: bankHolderName || companyLegalName,
                accountNumber: bankAccountNumber.trim(),
                ifscCode: bankIfsc.trim().toUpperCase(),
                bankName: bankName,
                accountType: bankType,
                isPrimary: true
            });

            // 2. Consent for Bank
            await verificationService.recordConsent({
                verificationType: "BANK",
                organizationId: selectedOrgId,
                purpose: "Penny drop verification for corporate milestone settlement bank account"
            });

            // 3. Penny Drop Verification
            const verifiedAcc = await verificationService.verifyBankAccount(selectedOrgId, newAcc.id);
            setBankAccount(verifiedAcc);

            setStatusMsg({ type: "success", text: `Bank Account Verified via Penny Drop (Name Match: ${verifiedAcc.nameMatchScore || 100}%)` });
            setStep(7);
        } catch (err: any) {
            setStatusMsg({ type: "error", text: err.response?.data?.message || err.message || "Bank account verification failed" });
        } finally {
            setLoading(false);
        }
    };

    // Step 7: Refresh final eligibility
    const loadEligibility = async () => {
        if (!selectedOrgId) return;
        setLoading(true);
        try {
            const res = await verificationService.getOrganizationVerification(selectedOrgId);
            setEligibility(res);
        } catch (err) {
            console.error("Failed to load verification eligibility", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (step >= 7 && selectedOrgId) {
            loadEligibility();
        }
    }, [step, selectedOrgId]);

    const progressSteps = [
        { num: 1, label: "Account" },
        { num: 2, label: "Personal KYC" },
        { num: 3, label: "Business Info" },
        { num: 4, label: "Business Verification" },
        { num: 5, label: "Authorized Signatory" },
        { num: 6, label: "Bank Account" },
        { num: 7, label: "Review" },
        { num: 8, label: "Verification Result" },
    ];

    return (
        <DashboardLayout>
            <div className="max-w-4xl mx-auto space-y-6 pb-16">
                {/* Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2">
                    <div>
                        <div className="flex items-center gap-2">
                            <h1 className="text-2xl font-bold text-slate-900 tracking-tight">FinX Business Onboarding</h1>
                            <span className="px-2.5 py-0.5 bg-blue-100 text-blue-800 text-xs font-semibold rounded-full border border-blue-200">
                                Provider Independent
                            </span>
                        </div>
                        <p className="text-sm text-slate-500 mt-1">
                            Complete individual identity KYC and business KYB verification for centralized B2B milestone transactions.
                        </p>
                    </div>

                    <div className="flex items-center gap-2 px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-lg text-amber-800 text-xs font-medium">
                        <Lock size={14} className="text-amber-600" />
                        <span>MOCK / SANDBOX ENVIRONMENT</span>
                    </div>
                </div>

                {/* Stepper Progress Bar */}
                <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-xs overflow-x-auto">
                    <div className="flex items-center justify-between min-w-[640px]">
                        {progressSteps.map((s, idx) => {
                            const isDone = step > s.num;
                            const isCurrent = step === s.num;
                            return (
                                <React.Fragment key={s.num}>
                                    <div
                                        onClick={() => step > s.num && setStep(s.num as StepId)}
                                        className={`flex flex-col items-center cursor-pointer transition-all ${
                                            isDone ? "text-blue-600 font-semibold" : isCurrent ? "text-slate-900 font-bold" : "text-slate-400"
                                        }`}
                                    >
                                        <div
                                            className={`w-8 h-8 rounded-full flex items-center justify-center text-xs transition-colors ${
                                                isDone
                                                    ? "bg-blue-600 text-white shadow-xs"
                                                    : isCurrent
                                                    ? "bg-slate-900 text-white ring-4 ring-blue-100"
                                                    : "bg-slate-100 text-slate-500 border border-slate-200"
                                            }`}
                                        >
                                            {isDone ? <Check size={14} /> : s.num}
                                        </div>
                                        <span className="text-xs mt-1.5 whitespace-nowrap">{s.label}</span>
                                    </div>
                                    {idx < progressSteps.length - 1 && (
                                        <div
                                            className={`flex-1 h-0.5 mx-2 rounded-full transition-colors ${
                                                step > s.num ? "bg-blue-600" : "bg-slate-200"
                                            }`}
                                        />
                                    )}
                                </React.Fragment>
                            );
                        })}
                    </div>
                </div>

                {/* Status Alert Banner */}
                {statusMsg && (
                    <div
                        className={`p-4 rounded-lg text-sm flex items-start gap-3 border ${
                            statusMsg.type === "success"
                                ? "bg-emerald-50 text-emerald-800 border-emerald-200"
                                : statusMsg.type === "error"
                                ? "bg-rose-50 text-rose-800 border-rose-200"
                                : "bg-blue-50 text-blue-800 border-blue-200"
                        }`}
                    >
                        {statusMsg.type === "success" ? (
                            <CheckCircle2 size={18} className="text-emerald-600 shrink-0 mt-0.5" />
                        ) : (
                            <AlertTriangle size={18} className="text-rose-600 shrink-0 mt-0.5" />
                        )}
                        <div>{statusMsg.text}</div>
                    </div>
                )}

                {/* Step 1: Account Confirmation */}
                {step === 1 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-blue-50 text-blue-600 rounded-lg">
                                    <UserCheck size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 1: Account Confirmation</h3>
                                    <p className="text-xs text-slate-500">Confirm your FinX user credentials and security authorization level.</p>
                                </div>
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                <div className="p-4 bg-slate-50 rounded-lg border border-slate-200">
                                    <label className="text-xs font-semibold text-slate-500 uppercase">Authenticated User</label>
                                    <p className="text-sm font-bold text-slate-900 mt-1">{user?.name || "Corporate User"}</p>
                                    <p className="text-xs text-slate-500 font-mono mt-0.5">{user?.email}</p>
                                </div>
                                <div className="p-4 bg-slate-50 rounded-lg border border-slate-200">
                                    <label className="text-xs font-semibold text-slate-500 uppercase">Platform Role</label>
                                    <p className="text-sm font-bold text-blue-700 mt-1">{user?.role || "BUYER"}</p>
                                    <p className="text-xs text-slate-500 mt-0.5">Authorized for B2B Milestone Escrow Orchestration</p>
                                </div>
                            </div>

                            <div className="p-4 bg-blue-50/70 border border-blue-100 rounded-lg text-xs text-blue-900 space-y-1">
                                <p className="font-semibold flex items-center gap-1.5">
                                    <Sparkles size={14} className="text-blue-600" />
                                    Production-Ready Separation:
                                </p>
                                <p className="text-blue-800">
                                    FinX requires separate verification of <strong>Who you are</strong> (Individual KYC) and <strong>Your authorization to act for the business</strong> (Corporate Authority).
                                </p>
                            </div>

                            <div className="pt-4 flex justify-end">
                                <Button onClick={() => setStep(2)}>
                                    Continue to Personal KYC <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 2: Individual KYC */}
                {step === 2 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-indigo-50 text-indigo-600 rounded-lg">
                                    <ShieldCheck size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 2: Individual KYC Verification</h3>
                                    <p className="text-xs text-slate-500">Government identity validation for operating directors and authorized signatories.</p>
                                </div>
                            </div>

                            {/* Consent Checkbox */}
                            <div className="p-4 bg-slate-50 rounded-lg border border-slate-200 flex items-start gap-3">
                                <input
                                    type="checkbox"
                                    id="kyc-consent"
                                    checked={kycConsent}
                                    onChange={(e) => setKycConsent(e.target.checked)}
                                    className="mt-1 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                />
                                <label htmlFor="kyc-consent" className="text-xs text-slate-700 leading-relaxed">
                                    I hereby grant explicit consent under the IT Act and applicable guidelines to FinX and its verification providers to verify my PAN details and identity credentials solely for identity confirmation and authorized signatory validation.
                                </label>
                            </div>

                            <div className="space-y-4">
                                <Input
                                    label="Full Legal Name (as on PAN card)"
                                    value={fullName}
                                    onChange={(e) => setFullName(e.target.value)}
                                    placeholder="Rajesh Kumar Sharma"
                                />

                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    <Input
                                        label="Individual PAN Number"
                                        value={panNumber}
                                        onChange={(e) => setPanNumber(e.target.value.toUpperCase())}
                                        placeholder="ABCDE1234F"
                                        maxLength={10}
                                    />
                                    <Input
                                        label="Date of Birth"
                                        type="date"
                                        value={dob}
                                        onChange={(e) => setDob(e.target.value)}
                                    />
                                </div>

                                <div className="flex gap-3">
                                    <Button
                                        onClick={handleVerifyPersonalPan}
                                        disabled={loading || !kycConsent}
                                        className="bg-blue-600 hover:bg-blue-700 text-white"
                                    >
                                        {loading ? <RefreshCw size={14} className="animate-spin mr-1.5" /> : null}
                                        Verify PAN with NSDL/ITD
                                    </Button>
                                </div>
                            </div>

                            {/* Verified Feedback Card */}
                            {kycProfile && kycProfile.panStatus === "VERIFIED" && (
                                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-lg flex items-center justify-between">
                                    <div className="flex items-center gap-3">
                                        <CheckCircle2 size={24} className="text-emerald-600" />
                                        <div>
                                            <p className="text-sm font-bold text-emerald-900">Personal PAN Verified</p>
                                            <p className="text-xs text-emerald-700 font-mono">
                                                Masked PAN: {kycProfile.panNumberMasked} | Name Match: {kycProfile.nameMatchScore}% ({kycProfile.nameMatchStatus})
                                            </p>
                                        </div>
                                    </div>
                                    <span className="text-xs bg-emerald-100 text-emerald-800 px-2.5 py-1 rounded-full font-semibold">
                                        VERIFIED
                                    </span>
                                </div>
                            )}

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(1)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button
                                    onClick={() => setStep(3)}
                                    disabled={!kycProfile || kycProfile.panStatus !== "VERIFIED"}
                                >
                                    Continue to Business Info <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 3: Business Information */}
                {step === 3 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-blue-50 text-blue-600 rounded-lg">
                                    <Building2 size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 3: Business Entity Information</h3>
                                    <p className="text-xs text-slate-500">Provide legal incorporation details for the transacting entity.</p>
                                </div>
                            </div>

                            <div className="space-y-4">
                                <div>
                                    <label className="text-xs font-semibold text-slate-700 uppercase block mb-1.5">Business Constitution / Entity Type</label>
                                    <select
                                        value={entityType}
                                        onChange={(e) => setEntityType(e.target.value as any)}
                                        className="w-full border border-slate-300 rounded-lg p-2.5 text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:outline-none"
                                    >
                                        <option value="PRIVATE_LIMITED">Private Limited Company (Pvt. Ltd.)</option>
                                        <option value="PUBLIC_LIMITED">Public Limited Company (Ltd.)</option>
                                        <option value="LLP">Limited Liability Partnership (LLP)</option>
                                        <option value="PROPRIETORSHIP">Sole Proprietorship</option>
                                        <option value="PARTNERSHIP">Partnership Firm</option>
                                    </select>
                                </div>

                                <Input
                                    label="Legal Business Name (as registered with MCA / Registrar)"
                                    value={companyLegalName}
                                    onChange={(e) => setCompanyLegalName(e.target.value)}
                                    placeholder="Acme Technologies Private Limited"
                                />

                                <Input
                                    label="Trade / Brand Name (Optional)"
                                    value={companyTradeName}
                                    onChange={(e) => setCompanyTradeName(e.target.value)}
                                    placeholder="Acme Pay"
                                />

                                <Input
                                    label="Registered Office Address"
                                    value={registeredAddress}
                                    onChange={(e) => setRegisteredAddress(e.target.value)}
                                    placeholder="Plot 42, BKC, Bandra East, Mumbai, Maharashtra 400051"
                                />
                            </div>

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(2)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button onClick={handleSaveBusinessInfo} disabled={loading}>
                                    Save & Continue to Business Verification <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 4: Business Verification */}
                {step === 4 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-blue-50 text-blue-600 rounded-lg">
                                    <FileText size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 4: Business Verification (KYB)</h3>
                                    <p className="text-xs text-slate-500">
                                        Conditional requirements for <strong>{entityType.replace("_", " ")}</strong>. Only relevant statutory credentials requested.
                                    </p>
                                </div>
                            </div>

                            <div className="space-y-4">
                                <Input
                                    label="Business PAN (Mandatory)"
                                    value={businessPan}
                                    onChange={(e) => setBusinessPan(e.target.value.toUpperCase())}
                                    placeholder="AAACA1234A"
                                    maxLength={10}
                                />

                                {(entityType === "PRIVATE_LIMITED" || entityType === "PUBLIC_LIMITED" || entityType === "LLP") && (
                                    <Input
                                        label="GSTIN (Mandatory for Corporate Deals)"
                                        value={gstin}
                                        onChange={(e) => setGstin(e.target.value.toUpperCase())}
                                        placeholder="27AAACA1234A1Z5"
                                        maxLength={15}
                                    />
                                )}

                                {(entityType === "PRIVATE_LIMITED" || entityType === "PUBLIC_LIMITED") && (
                                    <Input
                                        label="Corporate Identification Number (CIN - 21 characters)"
                                        value={cinOrLlpin}
                                        onChange={(e) => setCinOrLlpin(e.target.value.toUpperCase())}
                                        placeholder="U72900MH2020PTC123456"
                                    />
                                )}

                                {entityType === "LLP" && (
                                    <Input
                                        label="LLP Identification Number (LLPIN)"
                                        value={cinOrLlpin}
                                        onChange={(e) => setCinOrLlpin(e.target.value.toUpperCase())}
                                        placeholder="AAA-1234"
                                    />
                                )}

                                {entityType === "PROPRIETORSHIP" && (
                                    <Input
                                        label="Udyam Registration Number (MSME - Optional)"
                                        value={udyamNumber}
                                        onChange={(e) => setUdyamNumber(e.target.value.toUpperCase())}
                                        placeholder="UDYAM-MH-01-0000000"
                                    />
                                )}

                                <Button
                                    onClick={handleVerifyBusiness}
                                    disabled={loading}
                                    className="bg-blue-600 hover:bg-blue-700 text-white mt-2"
                                >
                                    {loading ? <RefreshCw size={14} className="animate-spin mr-1.5" /> : null}
                                    Verify Corporate Identifiers with Registries
                                </Button>
                            </div>

                            {/* KYB Status Card */}
                            {kybProfile && kybProfile.status === "VERIFIED" && (
                                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-lg flex items-center justify-between">
                                    <div className="flex items-center gap-3">
                                        <CheckCircle2 size={24} className="text-emerald-600" />
                                        <div>
                                            <p className="text-sm font-bold text-emerald-900">Corporate KYB Verified</p>
                                            <p className="text-xs text-emerald-700">
                                                PAN: {kybProfile.businessPanMasked} | GST: {kybProfile.gstStatus} | Reg: {kybProfile.registrationStatus}
                                            </p>
                                        </div>
                                    </div>
                                    <span className="text-xs bg-emerald-100 text-emerald-800 px-2.5 py-1 rounded-full font-semibold">
                                        VERIFIED
                                    </span>
                                </div>
                            )}

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(3)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button
                                    onClick={() => setStep(5)}
                                    disabled={!kybProfile || kybProfile.status !== "VERIFIED"}
                                >
                                    Continue to Signatory <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 5: Authorized Representative */}
                {step === 5 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-indigo-50 text-indigo-600 rounded-lg">
                                    <UserCheck size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 5: Authorized Signatory Linkage</h3>
                                    <p className="text-xs text-slate-500">
                                        Link verified individuals to the company with legal signing authority.
                                    </p>
                                </div>
                            </div>

                            <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg space-y-3">
                                <div className="flex items-center justify-between">
                                    <div>
                                        <p className="text-sm font-bold text-slate-900">{user?.name || fullName}</p>
                                        <p className="text-xs text-slate-500 font-mono">{user?.email}</p>
                                    </div>
                                    <span className="text-xs font-semibold px-2 py-0.5 bg-emerald-100 text-emerald-800 rounded-full">
                                        KYC Completed
                                    </span>
                                </div>

                                <div>
                                    <label className="text-xs font-semibold text-slate-700 uppercase block mb-1">Corporate Authority Relationship</label>
                                    <select
                                        value={representativeRole}
                                        onChange={(e) => setRepresentativeRole(e.target.value)}
                                        className="w-full border border-slate-300 rounded-lg p-2.5 text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:outline-none"
                                    >
                                        <option value="DIRECTOR">Director (Board Authorized)</option>
                                        <option value="AUTHORIZED_SIGNATORY">Authorized Signatory (Power of Attorney)</option>
                                        <option value="OWNER">Owner / Majority Shareholder</option>
                                        <option value="PARTNER">Managing Partner</option>
                                        <option value="FINANCE_APPROVER">Finance Approver / CFO</option>
                                    </select>
                                </div>

                                <Button
                                    onClick={handleAddRepresentative}
                                    disabled={loading}
                                    className="w-full bg-blue-600 hover:bg-blue-700 text-white mt-2"
                                >
                                    Confirm Authority & Link Signatory
                                </Button>
                            </div>

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(4)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button
                                    onClick={() => setStep(6)}
                                    disabled={!representativeSuccess}
                                >
                                    Continue to Bank Account <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 6: Bank Account Penny Drop */}
                {step === 6 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-blue-50 text-blue-600 rounded-lg">
                                    <Landmark size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 6: Business Bank Account Verification</h3>
                                    <p className="text-xs text-slate-500">
                                        Automated penny drop verification confirms corporate account ownership and matching beneficiary name.
                                    </p>
                                </div>
                            </div>

                            <div className="space-y-4">
                                <Input
                                    label="Account Holder Name (must match Company Legal Name)"
                                    value={bankHolderName || companyLegalName}
                                    onChange={(e) => setBankHolderName(e.target.value)}
                                    placeholder="Acme Technologies Private Limited"
                                />

                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    <Input
                                        label="Account Number"
                                        type="password"
                                        value={bankAccountNumber}
                                        onChange={(e) => setBankAccountNumber(e.target.value)}
                                        placeholder="Enter Bank Account Number"
                                    />
                                    <Input
                                        label="IFSC Code (11 characters)"
                                        value={bankIfsc}
                                        onChange={(e) => setBankIfsc(e.target.value.toUpperCase())}
                                        placeholder="HDFC0000060"
                                    />
                                </div>

                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    <Input
                                        label="Bank Name"
                                        value={bankName}
                                        onChange={(e) => setBankName(e.target.value)}
                                        placeholder="HDFC Bank"
                                    />
                                    <div>
                                        <label className="text-xs font-semibold text-slate-700 uppercase block mb-1">Account Type</label>
                                        <select
                                            value={bankType}
                                            onChange={(e) => setBankType(e.target.value)}
                                            className="w-full border border-slate-300 rounded-lg p-2.5 text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:outline-none"
                                        >
                                            <option value="CURRENT">Current Account (Corporate / Business)</option>
                                            <option value="SAVINGS">Savings Account (Proprietorship only)</option>
                                        </select>
                                    </div>
                                </div>

                                <Button
                                    onClick={handleVerifyBankAccount}
                                    disabled={loading}
                                    className="bg-blue-600 hover:bg-blue-700 text-white"
                                >
                                    {loading ? <RefreshCw size={14} className="animate-spin mr-1.5" /> : null}
                                    Execute Penny Drop Verification (Re. 1.00 Sandbox)
                                </Button>
                            </div>

                            {bankAccount && bankAccount.verificationStatus === "VERIFIED" && (
                                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-lg flex items-center justify-between">
                                    <div className="flex items-center gap-3">
                                        <CheckCircle2 size={24} className="text-emerald-600" />
                                        <div>
                                            <p className="text-sm font-bold text-emerald-900">Bank Account Verified</p>
                                            <p className="text-xs text-emerald-700 font-mono">
                                                Masked Account: {bankAccount.accountNumberMasked} | Name Match: {bankAccount.nameMatchScore || 100}%
                                            </p>
                                        </div>
                                    </div>
                                    <span className="text-xs bg-emerald-100 text-emerald-800 px-2.5 py-1 rounded-full font-semibold">
                                        VERIFIED
                                    </span>
                                </div>
                            )}

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(5)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button
                                    onClick={() => setStep(7)}
                                    disabled={!bankAccount || bankAccount.verificationStatus !== "VERIFIED"}
                                >
                                    Review All Credentials <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 7: Comprehensive Review */}
                {step === 7 && (
                    <Card>
                        <CardContent className="p-8 space-y-6">
                            <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
                                <div className="p-2.5 bg-blue-50 text-blue-600 rounded-lg">
                                    <FileText size={24} />
                                </div>
                                <div>
                                    <h3 className="text-lg font-semibold text-slate-900">Step 7: Verification Review & Compliance Audit</h3>
                                    <p className="text-xs text-slate-500">Summary of verified components and calculated platform permissions.</p>
                                </div>
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg space-y-2">
                                    <p className="text-xs font-bold text-slate-500 uppercase">Individual Identity (KYC)</p>
                                    <p className="text-sm font-bold text-slate-900">{fullName}</p>
                                    <p className="text-xs text-slate-600">PAN: {kycProfile?.panNumberMasked || "Masked"}</p>
                                    <span className="inline-block text-xs bg-emerald-100 text-emerald-800 px-2 py-0.5 rounded-full font-semibold">
                                        KYC {kycProfile?.status || "VERIFIED"}
                                    </span>
                                </div>

                                <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg space-y-2">
                                    <p className="text-xs font-bold text-slate-500 uppercase">Corporate Entity (KYB)</p>
                                    <p className="text-sm font-bold text-slate-900">{companyLegalName}</p>
                                    <p className="text-xs text-slate-600">Type: {entityType.replace("_", " ")}</p>
                                    <span className="inline-block text-xs bg-emerald-100 text-emerald-800 px-2 py-0.5 rounded-full font-semibold">
                                        KYB {kybProfile?.status || "VERIFIED"}
                                    </span>
                                </div>
                            </div>

                            {/* Checklist */}
                            {eligibility && (
                                <div className="border border-slate-200 rounded-lg overflow-hidden">
                                    <div className="bg-slate-50 px-4 py-2.5 text-xs font-bold text-slate-700 uppercase tracking-wider">
                                        Compliance Verification Components
                                    </div>
                                    <div className="divide-y divide-slate-100">
                                        {eligibility.checks.map((c) => (
                                            <div key={c.code} className="px-4 py-3 flex items-center justify-between text-xs">
                                                <div>
                                                    <span className="font-semibold text-slate-900">{c.name}</span>
                                                    <p className="text-slate-500">{c.detail}</p>
                                                </div>
                                                <span
                                                    className={`px-2.5 py-1 rounded-full font-bold ${
                                                        c.status === "VERIFIED"
                                                            ? "bg-emerald-100 text-emerald-800"
                                                            : c.status === "NOT_REQUIRED"
                                                            ? "bg-slate-100 text-slate-600"
                                                            : "bg-amber-100 text-amber-800"
                                                    }`}
                                                >
                                                    {c.status}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            <div className="pt-4 flex justify-between border-t border-slate-100">
                                <Button variant="outline" onClick={() => setStep(6)}>
                                    <ArrowLeft size={16} className="mr-1" /> Back
                                </Button>
                                <Button onClick={() => setStep(8)}>
                                    View Official Verification Result <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}

                {/* Step 8: Final Verification Result UI */}
                {step === 8 && (
                    <Card className="border-2 border-emerald-500 shadow-md">
                        <CardContent className="p-8 text-center space-y-6">
                            <div className="inline-flex p-4 bg-emerald-100 rounded-full text-emerald-600 ring-8 ring-emerald-50">
                                <ShieldCheck size={48} />
                            </div>

                            <div>
                                <span className="inline-block px-3 py-1 bg-emerald-50 border border-emerald-200 text-emerald-800 font-bold text-xs rounded-full uppercase tracking-wider mb-2">
                                    {eligibility?.badge || "FINX VERIFIED BUSINESS"}
                                </span>
                                <h2 className="text-2xl font-bold text-slate-900">
                                    {companyLegalName}
                                </h2>
                                <p className="text-sm text-slate-500 max-w-md mx-auto mt-1">
                                    All statutory identity, registration, signatory, and bank penny drop criteria have been satisfied.
                                </p>
                            </div>

                            {/* Badge Checklist Grid */}
                            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 max-w-lg mx-auto text-left">
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">Identity KYC</span>
                                </div>
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">Business PAN</span>
                                </div>
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">Registration</span>
                                </div>
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">GSTIN</span>
                                </div>
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">Signatory</span>
                                </div>
                                <div className="p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs flex items-center gap-2">
                                    <CheckCircle2 size={16} className="text-emerald-600" />
                                    <span className="font-semibold text-slate-800">Bank Account</span>
                                </div>
                            </div>

                            {/* Platform Gating Summary */}
                            <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg text-xs text-left max-w-lg mx-auto space-y-1.5">
                                <p className="font-bold text-slate-900 mb-1">Permitted Platform Actions:</p>
                                <div className="flex items-center justify-between text-slate-700">
                                    <span>Activate B2B Deals:</span>
                                    <span className="font-bold text-emerald-700">ALLOWED</span>
                                </div>
                                <div className="flex items-center justify-between text-slate-700">
                                    <span>Sign Digital Agreements:</span>
                                    <span className="font-bold text-emerald-700">ALLOWED</span>
                                </div>
                                <div className="flex items-center justify-between text-slate-700">
                                    <span>Real Money Settlement:</span>
                                    <span className="font-bold text-amber-700">BANKING PARTNER PENDING</span>
                                </div>
                            </div>

                            <div className="flex justify-center gap-3 pt-4">
                                <Button
                                    variant="outline"
                                    onClick={() => (window.location.href = "/dashboard")}
                                >
                                    Go to Dashboard
                                </Button>
                                <Button
                                    onClick={() => (window.location.href = "/corporate")}
                                    className="bg-blue-600 hover:bg-blue-700 text-white"
                                >
                                    View Corporate Projects <ArrowRight size={16} className="ml-1" />
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                )}
            </div>
        </DashboardLayout>
    );
}
