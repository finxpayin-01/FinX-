"use client";

import React, { useState, useEffect } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { authService } from "@/services/auth.service";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { LogOut, User, Lock, Bell, Settings as SettingsIcon, Building, ShieldAlert, AlertCircle, RefreshCw, Copy, Check } from "lucide-react";

export const FINXSettings = () => {
    const { user, logout, setSession } = useAuth();
    const [activeTab, setActiveTab] = useState("profile");
    const [currentUser, setCurrentUser] = useState(user);
    const [copiedUid, setCopiedUid] = useState(false);

    useEffect(() => {
        if (user) {
            setCurrentUser(user);
            if (!user.uid) {
                authService.getCurrentUser().then(fresh => {
                    if (fresh && fresh.uid) {
                        setCurrentUser(fresh);
                        const token = typeof window !== "undefined" ? localStorage.getItem("accessToken") || "" : "";
                        const refresh = typeof window !== "undefined" ? localStorage.getItem("refreshToken") || "" : "";
                        setSession(token, refresh, fresh);
                    }
                }).catch(err => {
                    console.warn("Could not fetch fresh user profile:", err);
                });
            }
        }
    }, [user, setSession]);

    const handleCopyUid = async () => {
        const uidToCopy = currentUser?.uid || user?.uid;
        if (!uidToCopy) return;
        try {
            if (navigator?.clipboard?.writeText) {
                await navigator.clipboard.writeText(uidToCopy);
            } else {
                const el = document.createElement("textarea");
                el.value = uidToCopy;
                document.body.appendChild(el);
                el.select();
                document.execCommand("copy");
                document.body.removeChild(el);
            }
            setCopiedUid(true);
            setTimeout(() => setCopiedUid(false), 2000);
        } catch (e) {
            console.error("Failed to copy UID", e);
        }
    };

    // Local Preferences State
    const [preferences, setPreferences] = useState({
        theme: "light",
        language: "en-US",
        currency: "INR",
        timezone: "UTC",
    });

    // Local Notifications State
    const [notifications, setNotifications] = useState({
        email: true,
        dealUpdates: true,
        milestoneUpdates: false,
        paymentNotifications: true,
        escrowNotifications: true,
        disputeNotifications: true,
        securityAlerts: true,
    });

    const [isSavingPref, setIsSavingPref] = useState(false);
    const [prefSuccess, setPrefSuccess] = useState("");

    useEffect(() => {
        const storedPrefs = localStorage.getItem("finx_preferences");
        if (storedPrefs) setPreferences(JSON.parse(storedPrefs));

        const storedNotif = localStorage.getItem("finx_notifications");
        if (storedNotif) setNotifications(JSON.parse(storedNotif));
    }, []);

    const savePreferences = () => {
        setIsSavingPref(true);
        setTimeout(() => {
            localStorage.setItem("finx_preferences", JSON.stringify(preferences));
            setIsSavingPref(false);
            setPrefSuccess("Preferences saved locally (Backend sync unavailable).");
            setTimeout(() => setPrefSuccess(""), 3000);
        }, 500);
    };

    const saveNotifications = () => {
        setIsSavingPref(true);
        setTimeout(() => {
            localStorage.setItem("finx_notifications", JSON.stringify(notifications));
            setIsSavingPref(false);
            setPrefSuccess("Notification preferences saved locally.");
            setTimeout(() => setPrefSuccess(""), 3000);
        }, 500);
    };

    const tabs = [
        { id: "profile", label: "Profile", icon: User },
        { id: "security", label: "Account Security", icon: Lock },
        { id: "notifications", label: "Notifications", icon: Bell },
        { id: "preferences", label: "Preferences", icon: SettingsIcon },
        ...(user?.role === "CORPORATE" || user?.role === "VENDOR" ? [{ id: "organization", label: "Organization", icon: Building }] : []),
        { id: "danger", label: "Danger Zone", icon: ShieldAlert },
    ];

    const handleLogout = () => {
        if (window.confirm("Are you sure you want to sign out?")) {
            logout();
        }
    };

    const renderTabContent = () => {
        switch (activeTab) {
            case "profile":
                return (
                    <Card>
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>Profile Information</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-4">
                            <div className="bg-slate-50 p-4 rounded text-sm text-slate-500 mb-4 border border-slate-200">
                                Profile updates are currently disabled pending the rollout of the Backend Profiles API.
                            </div>
                            <div>
                                <div className="flex items-center justify-between mb-1.5">
                                    <label className="block text-sm font-semibold text-slate-800">User Tracker (UID)</label>
                                    <span className="text-[11px] font-medium text-blue-700 bg-blue-50 border border-blue-200 px-2 py-0.5 rounded-full">
                                        Permanent &amp; Immutable
                                    </span>
                                </div>
                                <div className="relative flex items-center">
                                    <Input
                                        readOnly
                                        value={currentUser?.uid || user?.uid || "Generating UID..."}
                                        className="font-mono font-bold tracking-wider text-slate-900 bg-slate-50 border-slate-300 pr-32 select-all h-11 text-base"
                                    />
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        onClick={handleCopyUid}
                                        disabled={!(currentUser?.uid || user?.uid)}
                                        className="absolute right-1.5 h-8 px-3 text-xs font-semibold text-slate-700 hover:text-blue-600 border-slate-200 hover:border-blue-300 flex items-center gap-1.5 bg-white shadow-xs transition-colors"
                                    >
                                        {copiedUid ? (
                                            <>
                                                <Check className="w-3.5 h-3.5 text-emerald-600" />
                                                <span className="text-emerald-700">Copied!</span>
                                            </>
                                        ) : (
                                            <>
                                                <Copy className="w-3.5 h-3.5" />
                                                <span>Copy UID</span>
                                            </>
                                        )}
                                    </Button>
                                </div>
                                <p className="text-xs text-slate-500 mt-1.5">
                                    Share this UID with trusted FINX users to connect or assign projects.
                                </p>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Full Name</label>
                                <Input disabled value={currentUser?.name || currentUser?.fullName || user?.name || user?.fullName || "User"} />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Email Address</label>
                                <Input disabled value={currentUser?.email || user?.email || ""} />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Role / Account Type</label>
                                <Input disabled value={currentUser?.role || user?.role || "UNKNOWN"} />
                            </div>
                            <Button disabled className="mt-4">Update Profile</Button>
                        </CardContent>
                    </Card>
                );

            case "security":
                return (
                    <Card>
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>Account Security</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-4">
                            <div className="bg-amber-50 text-amber-700 p-4 rounded text-sm mb-4 border border-amber-200 flex items-start gap-3">
                                <AlertCircle size={16} className="mt-0.5 shrink-0" />
                                <p>Password change will be available after the security API is enabled.</p>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Current Password</label>
                                <Input disabled type="password" placeholder="••••••••" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">New Password</label>
                                <Input disabled type="password" placeholder="••••••••" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Confirm New Password</label>
                                <Input disabled type="password" placeholder="••••••••" />
                            </div>
                            <div className="flex gap-4 pt-2">
                                <Button disabled className="gap-2"><Lock size={16} /> Update Password</Button>
                                <Button variant="outline" onClick={handleLogout} className="gap-2 border-slate-300 text-slate-700 hover:bg-slate-50">
                                    <LogOut size={16} /> Logout Current Session
                                </Button>
                            </div>
                        </CardContent>
                    </Card>
                );

            case "notifications":
                return (
                    <Card>
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>Notification Preferences</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-6">
                            <div className="text-sm text-slate-500 mb-4">
                                Settings below are saved to your local browser storage. Push subscriptions are not currently synced with the FINX server.
                            </div>
                            {Object.entries(notifications).map(([key, value]) => (
                                <div key={key} className="flex items-center justify-between">
                                    <div>
                                        <p className="font-medium text-slate-900 capitalize">{key.replace(/([A-Z])/g, ' $1').trim()}</p>
                                    </div>
                                    <label className="relative inline-flex items-center cursor-pointer">
                                        <input
                                            type="checkbox"
                                            className="sr-only peer"
                                            checked={value}
                                            onChange={(e) => setNotifications({ ...notifications, [key]: e.target.checked })}
                                        />
                                        <div className="w-11 h-6 bg-slate-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-blue-600"></div>
                                    </label>
                                </div>
                            ))}
                            <div className="pt-4 border-t border-slate-100">
                                <Button onClick={saveNotifications} disabled={isSavingPref} className="gap-2">
                                    {isSavingPref ? <RefreshCw size={16} className="animate-spin" /> : null}
                                    Save Notification Settings
                                </Button>
                                {prefSuccess && <p className="text-emerald-600 text-sm mt-3">{prefSuccess}</p>}
                            </div>
                        </CardContent>
                    </Card>
                );

            case "preferences":
                return (
                    <Card>
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>System Preferences</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-4">
                            <div className="text-sm text-slate-500 mb-4">
                                Interface settings apply only to this browser device globally across FINX modules.
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Theme</label>
                                <select
                                    className="w-full h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    value={preferences.theme}
                                    onChange={e => setPreferences({ ...preferences, theme: e.target.value })}
                                >
                                    <option value="light" className="text-slate-900 bg-white">Light Mode (Default)</option>
                                    <option value="dark" className="text-slate-900 bg-white">Dark Mode</option>
                                    <option value="system" className="text-slate-900 bg-white">System Default</option>
                                </select>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Interface Language</label>
                                <select
                                    className="w-full h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    value={preferences.language}
                                    onChange={e => setPreferences({ ...preferences, language: e.target.value })}
                                >
                                    <option value="en-US" className="text-slate-900 bg-white">English (US)</option>
                                    <option value="es-ES" className="text-slate-900 bg-white">Spanish</option>
                                    <option value="fr-FR" className="text-slate-900 bg-white">French</option>
                                </select>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Default Display Currency</label>
                                <select
                                    className="w-full h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    value={preferences.currency}
                                    onChange={e => setPreferences({ ...preferences, currency: e.target.value })}
                                >
                                    <option value="INR" className="text-slate-900 bg-white">INR (₹)</option>
                                </select>
                            </div>
                            <div className="pt-4 border-t border-slate-100">
                                <Button onClick={savePreferences} disabled={isSavingPref} className="gap-2">
                                    {isSavingPref ? <RefreshCw size={16} className="animate-spin" /> : null}
                                    Save Preferences
                                </Button>
                                {prefSuccess && <p className="text-emerald-600 text-sm mt-3">{prefSuccess}</p>}
                            </div>
                        </CardContent>
                    </Card>
                );

            case "organization":
                return (
                    <Card>
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>Organization Details</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-4">
                            <div className="bg-slate-50 p-4 rounded text-sm text-slate-500 mb-4 border border-slate-200">
                                Global organization profile configuration is managed by system administrators natively. Client writes are locked.
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Organization Name</label>
                                <Input disabled value="FINX Client Organization" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-700 mb-1">Tax / Registration ID</label>
                                <Input disabled value="PENDING-VERIFICATION" />
                            </div>
                            <Button disabled className="mt-4">Save Organization Info</Button>
                        </CardContent>
                    </Card>
                );

            case "danger":
                return (
                    <Card>
                        <CardHeader className="border-b border-red-100 pb-4">
                            <CardTitle className="text-red-600">Danger Zone</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4 space-y-6">
                            <div className="flex flex-col md:flex-row justify-between items-start md:items-center p-4 border border-slate-200 rounded gap-4">
                                <div>
                                    <h4 className="font-semibold text-slate-900">Sign Out of All Devices</h4>
                                    <p className="text-sm text-slate-500">Log out of every active session immediately.</p>
                                </div>
                                <Button variant="outline" className="shrink-0" onClick={handleLogout}>
                                    Sign Out Globally
                                </Button>
                            </div>
                            <div className="flex flex-col md:flex-row justify-between items-start md:items-center p-4 border border-red-200 bg-red-50 rounded gap-4">
                                <div>
                                    <h4 className="font-semibold text-red-700">Delete Account & Data</h4>
                                    <p className="text-sm text-red-600/80">Permanently remove your account. This action cannot be reversed.</p>
                                </div>
                                <div className="text-right">
                                    <p className="text-xs text-red-500 font-medium mb-2">Endpoint Unavailable/Restricted</p>
                                    <Button variant="outline" className="bg-white border-red-200 text-red-600 shrink-0 opacity-50 cursor-not-allowed uppercase text-xs" disabled>
                                        Delete Account
                                    </Button>
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                );

            default:
                return null;
        }
    };

    return (
        <DashboardLayout>
            <div className="max-w-5xl">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Account Settings</h1>
                    <p className="text-sm text-slate-500 mt-1">Manage your profile, security, and global FINX preferences.</p>
                </div>

                <div className="flex flex-col md:flex-row gap-8 mt-8">
                    <div className="w-full md:w-64 shrink-0">
                        <nav className="flex flex-col space-y-1">
                            {tabs.map((tab) => {
                                const Icon = tab.icon;
                                const isActive = activeTab === tab.id;
                                return (
                                    <button
                                        key={tab.id}
                                        onClick={() => setActiveTab(tab.id)}
                                        className={`flex items-center gap-3 px-4 py-3 rounded-md text-sm font-medium transition-colors text-left ${isActive
                                            ? "bg-blue-50 text-blue-700"
                                            : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                                            }`}
                                    >
                                        <Icon size={18} className={tab.id === 'danger' && !isActive ? 'text-red-400' : ''} />
                                        <span className={tab.id === 'danger' && !isActive ? 'text-red-500' : ''}>
                                            {tab.label}
                                        </span>
                                    </button>
                                );
                            })}
                        </nav>
                    </div>
                    <div className="flex-1 min-w-0">
                        {renderTabContent()}
                    </div>
                </div>
            </div>
        </DashboardLayout>
    );
};
