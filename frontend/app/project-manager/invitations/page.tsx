"use client";

import React, { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { projectManagerService } from "@/services/project-manager.service";
import { ProjectManagerInvitation } from "@/types";
import {
  Mail,
  CheckCircle2,
  XCircle,
  Clock,
  Briefcase,
  User,
  DollarSign,
  AlertCircle,
  RefreshCw,
  ArrowRight,
  ShieldCheck,
} from "lucide-react";
import Link from "next/link";

export default function ProjectManagerInvitationsPage() {
  const [invitations, setInvitations] = useState<ProjectManagerInvitation[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<"PENDING" | "HISTORY">("PENDING");
  const [actionLoadingId, setActionLoadingId] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Decline modal state
  const [declineTarget, setDeclineTarget] = useState<ProjectManagerInvitation | null>(null);
  const [declineReason, setDeclineReason] = useState("");

  const fetchInvitations = async () => {
    setIsLoading(true);
    setActionError(null);
    try {
      const data = await projectManagerService.getMyInvitations();
      setInvitations(data);
    } catch (err: any) {
      setActionError(err?.response?.data?.message || err.message || "Failed to load invitations");
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchInvitations();
  }, []);

  const handleAccept = async (invitation: ProjectManagerInvitation) => {
    setActionLoadingId(invitation.id);
    setActionSuccess(null);
    setActionError(null);
    try {
      await projectManagerService.acceptInvitation(invitation.id);
      setActionSuccess(`Successfully accepted invitation for "${invitation.dealTitle || "Project"}". You are now assigned as the Project Manager.`);
      await fetchInvitations();
    } catch (err: any) {
      setActionError(err?.response?.data?.message || err.message || "Failed to accept invitation");
    } finally {
      setActionLoadingId(null);
    }
  };

  const handleConfirmDecline = async () => {
    if (!declineTarget) return;
    setActionLoadingId(declineTarget.id);
    setActionSuccess(null);
    setActionError(null);
    try {
      await projectManagerService.declineInvitation(declineTarget.id, declineReason.trim() || undefined);
      setActionSuccess(`Declined invitation for "${declineTarget.dealTitle || "Project"}".`);
      setDeclineTarget(null);
      setDeclineReason("");
      await fetchInvitations();
    } catch (err: any) {
      setActionError(err?.response?.data?.message || err.message || "Failed to decline invitation");
    } finally {
      setActionLoadingId(null);
    }
  };

  const pendingInvitations = invitations.filter((i) => i.status === "PENDING");
  const historyInvitations = invitations.filter((i) => i.status !== "PENDING");

  const StatusBadge = ({ status }: { status: string }) => {
    switch (status) {
      case "PENDING":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800 border border-amber-200">
            <Clock size={12} /> Pending Review
          </span>
        );
      case "ACCEPTED":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200">
            <CheckCircle2 size={12} /> Accepted & Assigned
          </span>
        );
      case "DECLINED":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800 border border-rose-200">
            <XCircle size={12} /> Declined
          </span>
        );
      case "CANCELLED":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 text-slate-700 border border-slate-200">
            Cancelled by Buyer
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 text-slate-700">
            {status}
          </span>
        );
    }
  };

  return (
    <DashboardLayout>
      <div className="space-y-6 max-w-6xl">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-slate-900 tracking-tight">Project Invitations</h1>
            <p className="text-sm text-slate-500 mt-1">
              Review incoming corporate invitations and accept project manager assignments.
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={fetchInvitations}
            disabled={isLoading}
            className="self-start sm:self-auto gap-2"
          >
            <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} />
            Refresh
          </Button>
        </div>

        {/* Feedback Messages */}
        {actionSuccess && (
          <div className="p-4 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 flex items-center justify-between shadow-xs">
            <div className="flex items-center gap-3">
              <CheckCircle2 size={18} className="text-emerald-600 shrink-0" />
              <p className="text-sm font-medium">{actionSuccess}</p>
            </div>
            <button
              onClick={() => setActionSuccess(null)}
              className="text-xs font-semibold text-emerald-700 hover:text-emerald-900"
            >
              Dismiss
            </button>
          </div>
        )}

        {actionError && (
          <div className="p-4 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 flex items-center justify-between shadow-xs">
            <div className="flex items-center gap-3">
              <AlertCircle size={18} className="text-rose-600 shrink-0" />
              <p className="text-sm font-medium">{actionError}</p>
            </div>
            <button
              onClick={() => setActionError(null)}
              className="text-xs font-semibold text-rose-700 hover:text-rose-900"
            >
              Dismiss
            </button>
          </div>
        )}

        {/* Metrics Row */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <Card>
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Pending Action</p>
                <p className="text-2xl font-bold text-amber-600 mt-1">{pendingInvitations.length}</p>
              </div>
              <div className="h-10 w-10 bg-amber-50 rounded-full flex items-center justify-center text-amber-600">
                <Clock size={20} />
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Accepted</p>
                <p className="text-2xl font-bold text-emerald-600 mt-1">
                  {invitations.filter((i) => i.status === "ACCEPTED").length}
                </p>
              </div>
              <div className="h-10 w-10 bg-emerald-50 rounded-full flex items-center justify-center text-emerald-600">
                <CheckCircle2 size={20} />
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Total Received</p>
                <p className="text-2xl font-bold text-slate-900 mt-1">{invitations.length}</p>
              </div>
              <div className="h-10 w-10 bg-blue-50 rounded-full flex items-center justify-center text-blue-600">
                <Mail size={20} />
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Navigation Tabs */}
        <div className="flex border-b border-slate-200">
          <button
            onClick={() => setActiveTab("PENDING")}
            className={`pb-3 px-4 text-sm font-semibold border-b-2 transition-colors flex items-center gap-2 ${
              activeTab === "PENDING"
                ? "border-blue-600 text-blue-600"
                : "border-transparent text-slate-500 hover:text-slate-700"
            }`}
          >
            Pending Invitations
            {pendingInvitations.length > 0 && (
              <span className="px-2 py-0.5 text-xs rounded-full bg-amber-100 text-amber-800 font-bold">
                {pendingInvitations.length}
              </span>
            )}
          </button>
          <button
            onClick={() => setActiveTab("HISTORY")}
            className={`pb-3 px-4 text-sm font-semibold border-b-2 transition-colors flex items-center gap-2 ${
              activeTab === "HISTORY"
                ? "border-blue-600 text-blue-600"
                : "border-transparent text-slate-500 hover:text-slate-700"
            }`}
          >
            Invitation History
            <span className="text-xs text-slate-400">({historyInvitations.length})</span>
          </button>
        </div>

        {/* Tab Content: Pending Invitations */}
        {activeTab === "PENDING" && (
          <div className="space-y-4">
            {isLoading ? (
              <div className="p-12 text-center text-slate-500 flex flex-col items-center">
                <RefreshCw size={24} className="animate-spin text-blue-500 mb-2" />
                <p className="text-sm">Loading invitations...</p>
              </div>
            ) : pendingInvitations.length === 0 ? (
              <Card>
                <CardContent className="p-12 text-center flex flex-col items-center">
                  <div className="h-14 w-14 bg-slate-100 rounded-full flex items-center justify-center text-slate-400 mb-3">
                    <Mail size={24} />
                  </div>
                  <h3 className="text-base font-semibold text-slate-900">No Pending Invitations</h3>
                  <p className="text-xs text-slate-500 mt-1 max-w-md">
                    You currently have no project invitations requiring action. When a corporate buyer invites you using your PM ID, it will appear here for review.
                  </p>
                </CardContent>
              </Card>
            ) : (
              pendingInvitations.map((invitation) => (
                <Card key={invitation.id} className="border-amber-200/80 shadow-xs hover:border-amber-300 transition-colors">
                  <CardHeader className="pb-3 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2.5 flex-wrap">
                        <span className="font-mono text-xs font-semibold px-2 py-0.5 rounded bg-slate-100 text-slate-700">
                          {invitation.projectId || "PRJ-FINX"}
                        </span>
                        <CardTitle className="text-base font-bold text-slate-900">
                          {invitation.dealTitle || "Untitled Project"}
                        </CardTitle>
                      </div>
                      <p className="text-xs text-slate-500 flex items-center gap-2">
                        <span>Invited by <strong className="text-slate-700">{invitation.buyerName || invitation.invitedByName || "Corporate Buyer"}</strong></span>
                        <span>•</span>
                        <span>{new Date(invitation.invitedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}</span>
                      </p>
                    </div>
                    <StatusBadge status={invitation.status} />
                  </CardHeader>
                  <CardContent className="pt-4 space-y-4">
                    {invitation.dealDescription && (
                      <p className="text-sm text-slate-600 line-clamp-2">
                        {invitation.dealDescription}
                      </p>
                    )}

                    <div className="flex flex-wrap items-center justify-between gap-4 pt-2 border-t border-slate-100">
                      <div className="flex items-center gap-6">
                        <div>
                          <p className="text-xs font-medium text-slate-500">Project Value</p>
                          <p className="text-sm font-bold text-slate-900 mt-0.5">
                            ₹{Number(invitation.dealTotalAmount || 0).toLocaleString("en-IN")}{" "}
                            <span className="text-xs font-normal text-slate-500">{invitation.dealCurrency || "INR"}</span>
                          </p>
                        </div>
                        <div>
                          <p className="text-xs font-medium text-slate-500">Your Role</p>
                          <p className="text-sm font-semibold text-blue-600 mt-0.5 flex items-center gap-1">
                            <ShieldCheck size={14} /> Project Manager
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-2.5">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={actionLoadingId === invitation.id}
                          onClick={() => setDeclineTarget(invitation)}
                          className="text-rose-700 border-rose-200 hover:bg-rose-50 hover:border-rose-300 text-xs"
                        >
                          Decline
                        </Button>
                        <Button
                          size="sm"
                          disabled={actionLoadingId === invitation.id}
                          onClick={() => handleAccept(invitation)}
                          className="bg-emerald-600 hover:bg-emerald-700 text-white text-xs gap-1.5"
                        >
                          {actionLoadingId === invitation.id ? (
                            <RefreshCw size={14} className="animate-spin" />
                          ) : (
                            <CheckCircle2 size={14} />
                          )}
                          Accept Assignment
                        </Button>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))
            )}
          </div>
        )}

        {/* Tab Content: Invitation History */}
        {activeTab === "HISTORY" && (
          <div className="space-y-4">
            {isLoading ? (
              <div className="p-12 text-center text-slate-500 flex flex-col items-center">
                <RefreshCw size={24} className="animate-spin text-blue-500 mb-2" />
                <p className="text-sm">Loading history...</p>
              </div>
            ) : historyInvitations.length === 0 ? (
              <Card>
                <CardContent className="p-12 text-center flex flex-col items-center">
                  <div className="h-14 w-14 bg-slate-100 rounded-full flex items-center justify-center text-slate-400 mb-3">
                    <Clock size={24} />
                  </div>
                  <h3 className="text-base font-semibold text-slate-900">No Historical Invitations</h3>
                  <p className="text-xs text-slate-500 mt-1 max-w-md">
                    Past accepted, declined, or cancelled project invitations will appear here for reference.
                  </p>
                </CardContent>
              </Card>
            ) : (
              <Card>
                <CardContent className="p-0">
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm text-slate-600 border-collapse">
                      <thead className="bg-slate-50 text-xs uppercase font-semibold text-slate-500 border-b border-slate-100">
                        <tr>
                          <th className="py-3 px-4">Project</th>
                          <th className="py-3 px-4">Invited By</th>
                          <th className="py-3 px-4 text-right">Value</th>
                          <th className="py-3 px-4">Status</th>
                          <th className="py-3 px-4">Dates</th>
                          <th className="py-3 px-4 text-right">Actions</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {historyInvitations.map((inv) => (
                          <tr key={inv.id} className="hover:bg-slate-50/80 transition-colors">
                            <td className="py-3.5 px-4">
                              <div className="font-semibold text-slate-900">{inv.dealTitle || "Project"}</div>
                              <span className="font-mono text-xs text-slate-400">{inv.projectId || inv.dealId}</span>
                            </td>
                            <td className="py-3.5 px-4 text-xs text-slate-700">
                              {inv.buyerName || inv.invitedByName || "Corporate"}
                            </td>
                            <td className="py-3.5 px-4 text-right font-semibold text-slate-900">
                              ₹{Number(inv.dealTotalAmount || 0).toLocaleString("en-IN")}
                            </td>
                            <td className="py-3.5 px-4">
                              <StatusBadge status={inv.status} />
                              {inv.declineReason && (
                                <p className="text-xs text-rose-600 mt-1 italic line-clamp-1">
                                  "{inv.declineReason}"
                                </p>
                              )}
                            </td>
                            <td className="py-3.5 px-4 text-xs text-slate-500">
                              <div>Invited: {new Date(inv.invitedAt).toLocaleDateString("en-IN")}</div>
                              {inv.respondedAt && (
                                <div>Responded: {new Date(inv.respondedAt).toLocaleDateString("en-IN")}</div>
                              )}
                            </td>
                            <td className="py-3.5 px-4 text-right">
                              {inv.status === "ACCEPTED" ? (
                                <Link href="/project-manager/projects">
                                  <Button variant="outline" size="sm" className="gap-1 text-xs text-blue-600 h-7">
                                    View Project <ArrowRight size={12} />
                                  </Button>
                                </Link>
                              ) : (
                                <span className="text-xs text-slate-400">—</span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </CardContent>
              </Card>
            )}
          </div>
        )}

        {/* Decline Reason Modal */}
        {declineTarget && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4">
            <div className="bg-white rounded-xl max-w-md w-full p-6 shadow-xl space-y-4 border border-slate-200">
              <div className="flex items-start gap-3">
                <div className="h-10 w-10 bg-rose-50 text-rose-600 rounded-full flex items-center justify-center shrink-0">
                  <XCircle size={20} />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-900">Decline Project Invitation</h3>
                  <p className="text-xs text-slate-500 mt-0.5">
                    Decline assignment for "{declineTarget.dealTitle || "Project"}".
                  </p>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 mb-1">
                  Reason for Declining (Optional)
                </label>
                <textarea
                  value={declineReason}
                  onChange={(e) => setDeclineReason(e.target.value)}
                  placeholder="e.g. Current workload capacity exceeded, domain mismatch, etc."
                  rows={3}
                  className="w-full text-xs p-2.5 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 text-slate-900"
                />
              </div>

              <div className="flex items-center justify-end gap-2.5 pt-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setDeclineTarget(null);
                    setDeclineReason("");
                  }}
                  className="text-xs"
                >
                  Cancel
                </Button>
                <Button
                  size="sm"
                  onClick={handleConfirmDecline}
                  disabled={actionLoadingId === declineTarget.id}
                  className="bg-rose-600 hover:bg-rose-700 text-white text-xs gap-1.5"
                >
                  {actionLoadingId === declineTarget.id && <RefreshCw size={14} className="animate-spin" />}
                  Confirm Decline
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </DashboardLayout>
  );
}
