"use client";

import { useEffect, useState, useCallback } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card } from "@/components/ui/Card";
import { adminService } from "@/services/day6.service";
import { UserCheck, Key, X, CheckCircle2, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";

export default function UserManagementPage() {
    const [users, setUsers] = useState<any[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [selectedUser, setSelectedUser] = useState<any | null>(null);
    const [newPassword, setNewPassword] = useState("");
    const [isResetting, setIsResetting] = useState(false);
    const [resetSuccess, setResetSuccess] = useState<string | null>(null);
    const [resetError, setResetError] = useState<string | null>(null);

    const loadUsers = useCallback(() => {
        setIsLoading(true);
        adminService.getUsers()
            .then(data => setUsers(Array.isArray(data) ? data : []))
            .catch(console.error)
            .finally(() => setIsLoading(false));
    }, []);

    useEffect(() => {
        loadUsers();
    }, [loadUsers]);

    const handlePasswordReset = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!selectedUser || !newPassword) return;

        setIsResetting(true);
        setResetError(null);
        setResetSuccess(null);

        try {
            await adminService.resetPassword(selectedUser.id, newPassword);
            setResetSuccess(`Password for ${selectedUser.email} has been reset successfully.`);
            setNewPassword("");
            setTimeout(() => {
                setSelectedUser(null);
                setResetSuccess(null);
            }, 2000);
        } catch (err: any) {
            setResetError(err?.response?.data?.message || err.message || "Failed to reset password");
        } finally {
            setIsResetting(false);
        }
    };

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">User Identity & Access Management</h1>
                    <p className="text-sm text-slate-500 mt-1">Platform user directory, credential recovery, and role oversight.</p>
                </div>

                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">User Details</th>
                                    <th className="p-4 font-semibold text-slate-600">Access Role</th>
                                    <th className="p-4 font-semibold text-slate-600">System Status</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && (
                                    <tr>
                                        <td colSpan={4} className="p-8 text-center text-slate-500">Querying database...</td>
                                    </tr>
                                )}
                                {!isLoading && users.map((u: any) => (
                                    <tr key={u.id} className="hover:bg-slate-50 transition-colors">
                                        <td className="p-4">
                                            <p className="font-semibold text-slate-900">{u.name || u.fullName || "User"}</p>
                                            <p className="text-xs text-slate-500 font-mono">{u.email}</p>
                                        </td>
                                        <td className="p-4">
                                            <span className="inline-flex bg-indigo-50 text-indigo-700 px-2 py-1 rounded font-mono text-xs font-semibold">
                                                {u.role}
                                            </span>
                                        </td>
                                        <td className="p-4">
                                            <span className="inline-flex items-center gap-1 text-emerald-600 font-medium text-xs">
                                                <UserCheck size={16} /> ACTIVE
                                            </span>
                                        </td>
                                        <td className="p-4 text-right">
                                            <button
                                                onClick={() => {
                                                    setSelectedUser(u);
                                                    setNewPassword("");
                                                    setResetError(null);
                                                    setResetSuccess(null);
                                                }}
                                                className="inline-flex items-center gap-1 text-blue-600 hover:text-blue-800 font-medium text-xs border border-blue-200 bg-blue-50 px-3 py-1.5 rounded transition-colors"
                                            >
                                                <Key size={13} /> Reset Password
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </Card>

                {/* Password Reset Modal */}
                {selectedUser && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50 animate-in fade-in duration-150">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full p-6 border border-slate-200">
                            <div className="flex items-center justify-between pb-3 border-b border-slate-100">
                                <div>
                                    <h3 className="text-lg font-bold text-slate-900">Reset Account Password</h3>
                                    <p className="text-xs text-slate-500 font-mono mt-0.5">{selectedUser.email}</p>
                                </div>
                                <button
                                    onClick={() => setSelectedUser(null)}
                                    className="text-slate-400 hover:text-slate-600 p-1"
                                >
                                    <X size={18} />
                                </button>
                            </div>

                            <form onSubmit={handlePasswordReset} className="mt-4 space-y-4">
                                {resetError && (
                                    <div className="p-3 bg-red-50 text-red-700 border border-red-200 rounded-lg text-xs flex items-center gap-2">
                                        <AlertCircle size={16} className="shrink-0" />
                                        <span>{resetError}</span>
                                    </div>
                                )}

                                {resetSuccess && (
                                    <div className="p-3 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-lg text-xs flex items-center gap-2">
                                        <CheckCircle2 size={16} className="shrink-0" />
                                        <span>{resetSuccess}</span>
                                    </div>
                                )}

                                <div>
                                    <label className="block text-xs font-semibold text-slate-700 mb-1">
                                        New Password (min 8 characters)
                                    </label>
                                    <Input
                                        type="password"
                                        required
                                        minLength={8}
                                        placeholder="Enter new password"
                                        value={newPassword}
                                        onChange={(e) => setNewPassword(e.target.value)}
                                        disabled={isResetting || !!resetSuccess}
                                    />
                                </div>

                                <div className="flex justify-end gap-2 pt-2">
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        onClick={() => setSelectedUser(null)}
                                        disabled={isResetting}
                                    >
                                        Cancel
                                    </Button>
                                    <Button
                                        type="submit"
                                        size="sm"
                                        disabled={isResetting || !newPassword || newPassword.length < 8 || !!resetSuccess}
                                    >
                                        {isResetting ? "Updating..." : "Update Password"}
                                    </Button>
                                </div>
                            </form>
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
