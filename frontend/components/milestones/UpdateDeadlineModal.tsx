"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { Calendar, Clock, RefreshCw, X } from "lucide-react";

interface UpdateDeadlineModalProps {
    milestone: Milestone;
    isOpen: boolean;
    onClose: () => void;
    onSuccess: (updated: Milestone) => void;
}

export function UpdateDeadlineModal({ milestone, isOpen, onClose, onSuccess }: UpdateDeadlineModalProps) {
    const existingDeadline = milestone.submissionDeadline || milestone.dueDate;
    const initialDate = existingDeadline ? existingDeadline.split("T")[0] : new Date().toISOString().split("T")[0];
    const initialTime = existingDeadline && existingDeadline.includes("T")
        ? existingDeadline.split("T")[1].substring(0, 5)
        : "18:00";

    const [date, setDate] = useState(initialDate);
    const [time, setTime] = useState(initialTime);
    const [reason, setReason] = useState("");
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    if (!isOpen) return null;

    const userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);

        if (!date) {
            setError("Please select a date.");
            return;
        }

        const isoTimestamp = new Date(`${date}T${time || "18:00"}:00`).toISOString();

        setIsSubmitting(true);
        try {
            const updated = await milestoneService.updateDeadline(milestone.id, {
                newDeadline: isoTimestamp,
                reason: reason.trim() || "Deadline adjusted by authorized party",
            });
            onSuccess(updated);
            onClose();
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to update deadline");
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-xs p-4">
            <div className="bg-white rounded-2xl shadow-2xl border border-slate-200 w-full max-w-md overflow-hidden animate-in fade-in zoom-in-95 duration-150">
                <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                    <div>
                        <h2 className="text-lg font-bold text-slate-900">Update Submission Deadline</h2>
                        <p className="text-xs text-slate-500 mt-0.5">{milestone.title}</p>
                    </div>
                    <button
                        onClick={onClose}
                        className="text-slate-400 hover:text-slate-600 rounded-full p-1.5 transition-colors"
                    >
                        <X size={18} />
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="p-6 space-y-4">
                    {error && (
                        <div className="p-3 bg-red-50 text-red-700 text-xs rounded-lg border border-red-200">
                            {error}
                        </div>
                    )}

                    <div className="grid grid-cols-2 gap-3">
                        <div>
                            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                Deadline Date <span className="text-red-500">*</span>
                            </label>
                            <input
                                type="date"
                                value={date}
                                onChange={(e) => setDate(e.target.value)}
                                required
                                className="w-full h-10 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                            />
                        </div>

                        <div>
                            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                Time <span className="text-red-500">*</span>
                            </label>
                            <input
                                type="time"
                                value={time}
                                onChange={(e) => setTime(e.target.value)}
                                required
                                className="w-full h-10 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                            />
                        </div>
                    </div>

                    <div className="text-[11px] text-slate-500 flex items-center gap-1.5 bg-slate-50 p-2.5 rounded-lg border border-slate-100">
                        <Clock size={12} className="text-slate-400" />
                        <span>Displaying in your local timezone: <strong>{userTimezone}</strong></span>
                    </div>

                    <div>
                        <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                            Reason for Extension / Change
                        </label>
                        <textarea
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                            placeholder="e.g. Inclement weather delay, design revision approval..."
                            className="w-full rounded-lg border border-slate-200 p-2.5 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 min-h-[70px]"
                        />
                    </div>

                    <div className="pt-2 flex justify-end gap-3 border-t border-slate-100">
                        <Button type="button" variant="outline" size="sm" onClick={onClose} disabled={isSubmitting}>
                            Cancel
                        </Button>
                        <Button
                            type="submit"
                            size="sm"
                            disabled={isSubmitting}
                            className="bg-blue-600 hover:bg-blue-700 text-white font-medium gap-1.5"
                        >
                            {isSubmitting && <RefreshCw size={13} className="animate-spin" />}
                            Save & Reschedule Reminders
                        </Button>
                    </div>
                </form>
            </div>
        </div>
    );
}
