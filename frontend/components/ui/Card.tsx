import React from "react";

export const Card = ({ className = "", children, id }: { className?: string, children: React.ReactNode, id?: string }) => {
    return (
        <div id={id} className={`bg-white text-slate-900 rounded-lg border border-slate-200 shadow-sm ${className}`}>
            {children}
        </div>
    );
};

export const CardHeader = ({ className = "", children }: { className?: string, children: React.ReactNode }) => {
    return (
        <div className={`flex flex-col space-y-1.5 p-6 pb-4 ${className}`}>
            {children}
        </div>
    );
};

export const CardTitle = ({ className = "", children }: { className?: string, children: React.ReactNode }) => {
    return (
        <h3 className={`font-semibold leading-none tracking-tight text-lg text-slate-900 ${className}`}>
            {children}
        </h3>
    );
};

export const CardContent = ({ className = "", children }: { className?: string, children: React.ReactNode }) => {
    return (
        <div className={`p-6 pt-0 ${className}`}>
            {children}
        </div>
    );
};
