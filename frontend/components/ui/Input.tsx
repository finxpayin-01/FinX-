import React, { useState } from "react";
import { Eye, EyeOff } from "lucide-react";

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
    label?: string;
    error?: string;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
    ({ className = "", label, error, type, ...props }, ref) => {
        const [showPassword, setShowPassword] = useState(false);
        const isPasswordType = type === "password";

        const currentType = isPasswordType && showPassword ? "text" : type;

        const inputProps = { ...props };
        if (inputProps.value !== undefined) {
            inputProps.value = inputProps.value === null ? "" : inputProps.value;
        } else if ('value' in inputProps) {
            inputProps.value = "";
        }

        return (
            <div className="w-full">
                {label && (
                    <label className="block text-sm font-medium text-slate-700 mb-1">
                        {label}
                    </label>
                )}
                <div className="relative">
                    <input
                        ref={ref}
                        type={currentType}
                        className={`flex h-10 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500 disabled:border-slate-300 disabled:opacity-80 ${error ? "border-red-500 focus:ring-red-500" : ""
                            } ${isPasswordType ? "pr-10" : ""} ${className}`}
                        {...inputProps}
                    />
                    {isPasswordType && (
                        <button
                            type="button"
                            onClick={() => setShowPassword(!showPassword)}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 focus:outline-none"
                            aria-label={showPassword ? "Hide password" : "Show password"}
                        >
                            {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                        </button>
                    )}
                </div>
                {error && <p className="mt-1 text-sm text-red-500">{error}</p>}
            </div>
        );
    }
);
Input.displayName = "Input";
