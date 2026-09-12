import { CheckCircle2, Circle } from "lucide-react";
import { MilestoneStatus } from "@/types";

export function EscrowTimeline({ currentStatus }: { currentStatus: MilestoneStatus }) {
    const defaultSteps = [
        { key: 'PENDING', label: 'Milestone Created', desc: 'Awaiting corporate funding' },
        { key: 'IN_PROGRESS', label: 'Buyer Funded', desc: 'Vendor working on deliverables' },
        { key: 'SUBMITTED', label: 'Deliverable Submitted', desc: 'Vendor uploaded work' },
        { key: 'UNDER_REVIEW', label: 'PM Review', desc: 'Arbitrating deliverables' },
        { key: 'APPROVED', label: 'Milestone Approved', desc: 'Pending admin escrow transfer' },
        { key: 'RELEASED', label: 'Funds Released', desc: 'Fiat successfully cleared' }
    ];

    const getStatusIndex = (status: string) => {
        if (status === 'PENDING') return 0;
        if (status === 'IN_PROGRESS') return 1;
        if (status === 'SUBMITTED') return 2;
        if (status === 'UNDER_REVIEW') return 3;
        if (status === 'APPROVED' || status === 'RELEASE_PENDING') return 4;
        if (status === 'RELEASED' || status === 'COMPLETED') return 5;
        return 0;
    };

    const currentIndex = getStatusIndex(currentStatus);

    return (
        <div className="py-4">
            <h3 className="text-sm font-semibold text-slate-900 mb-6">Escrow Transaction Lifecycle</h3>
            <div className="relative border-l-2 border-slate-200 ml-3 space-y-8">
                {defaultSteps.map((step, idx) => {
                    const isCompleted = idx <= currentIndex;
                    const isActive = idx === currentIndex;

                    return (
                        <div key={idx} className="relative pl-8">
                            <div className="absolute -left-[11px] bg-white pt-1">
                                {isCompleted ? (
                                    <CheckCircle2 size={20} className={isActive ? "text-blue-600" : "text-emerald-500"} />
                                ) : (
                                    <Circle size={20} className="text-slate-300" />
                                )}
                            </div>
                            <div>
                                <p className={`text-sm font-semibold ${isCompleted ? 'text-slate-900' : 'text-slate-400'}`}>
                                    {step.label}
                                </p>
                                <p className="text-xs text-slate-500 mt-1">{step.desc}</p>
                            </div>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
