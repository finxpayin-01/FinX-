import { MilestoneStatus } from '@/types';

export function MilestoneStatusBadge({ status }: { status: MilestoneStatus }) {
    const styles: Record<MilestoneStatus, string> = {
        PENDING: 'bg-slate-100 text-slate-700 ring-slate-600/20',
        IN_PROGRESS: 'bg-blue-50 text-blue-700 ring-blue-600/20',
        SUBMITTED: 'bg-indigo-50 text-indigo-700 ring-indigo-600/20',
        UNDER_REVIEW: 'bg-amber-50 text-amber-700 ring-amber-600/20',
        APPROVED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
        REJECTED: 'bg-red-50 text-red-700 ring-red-600/20',
        RELEASE_PENDING: 'bg-cyan-50 text-cyan-700 ring-cyan-600/20',
        RELEASED: 'bg-green-100 text-green-800 ring-green-600/30 font-bold',
        COMPLETED: 'bg-emerald-100 text-emerald-800 ring-emerald-600/30 font-bold',
        CANCELLED: 'bg-slate-100 text-slate-500 ring-slate-400/20 line-through',
    };

    const labels: Record<MilestoneStatus, string> = {
        PENDING: 'Pending Funding',
        IN_PROGRESS: 'In Progress',
        SUBMITTED: 'Deliverables Uploaded',
        UNDER_REVIEW: 'Under PM Review',
        APPROVED: 'Approved',
        REJECTED: 'Rejected (Needs Work)',
        RELEASE_PENDING: 'Escrow Action Pending',
        RELEASED: 'Escrow Released',
        COMPLETED: 'Completed (Released)',
        CANCELLED: 'Cancelled',
    };

    return (
        <span className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset ${styles[status]}`}>
            {labels[status]}
        </span>
    );
}
