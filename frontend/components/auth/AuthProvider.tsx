"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { User, Role } from "@/types";
import { authService } from "@/services/auth.service";
import { clearAuthSession } from "@/lib/api";
import { useRouter, usePathname } from "next/navigation";

interface AuthContextType {
    user: User | null;
    isLoading: boolean;
    login: (data: Record<string, string>) => Promise<void>;
    register: (data: Record<string, any>) => Promise<void>;
    googleLogin: (code: string) => Promise<void>;
    setSession: (accessToken: string, refreshToken: string, user: User) => void;
    logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
    const [user, setUser] = useState<User | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();
    const pathname = usePathname();

    const redirectBasedOnRole = useCallback((role: Role | string) => {
        let target = "/corporate";
        const r = (role || "").toUpperCase();
        if (r === "ADMIN") target = "/admin";
        else if (r === "FINANCE") target = "/finance";
        else if (r === "PROJECT_MANAGER" || r === "PROJ_MANAGER") target = "/project-manager";
        else if (r === "VENDOR" || r === "SELLER") target = "/vendor";
        else if (r === "BUYER" || r === "CORPORATE") target = "/corporate";

        router.push(target);
        if (typeof window !== "undefined") {
            setTimeout(() => {
                if (window.location.pathname === "/register" || window.location.pathname === "/login") {
                    window.location.href = target;
                }
            }, 600);
        }
    }, [router]);

    const setSession = useCallback((accessToken: string, refreshToken: string, userData: User) => {
        const normalized: User = {
            ...userData,
            fullName: userData.name || userData.fullName || "User",
            name: userData.name || userData.fullName || "User",
        };
        localStorage.setItem("accessToken", accessToken);
        localStorage.setItem("refreshToken", refreshToken);
        localStorage.setItem("user", JSON.stringify(normalized));
        setUser(normalized);
    }, []);

    useEffect(() => {
        const initAuth = async () => {
            const token = typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;
            const storedUser = typeof window !== "undefined" ? localStorage.getItem("user") : null;

            if (token) {
                if (storedUser) {
                    try {
                        const parsed = JSON.parse(storedUser);
                        parsed.fullName = parsed.name || parsed.fullName || "User";
                        parsed.name = parsed.name || parsed.fullName || "User";
                        setUser(parsed);
                    } catch (e) {
                        console.error("Failed to parse stored user", e);
                    }
                }

                // Verify session against backend /api/auth/me
                try {
                    const freshUser = await authService.getCurrentUser();
                    freshUser.fullName = freshUser.name || freshUser.fullName || "User";
                    freshUser.name = freshUser.name || freshUser.fullName || "User";
                    localStorage.setItem("user", JSON.stringify(freshUser));
                    setUser(freshUser);
                } catch (e: any) {
                    // Only clear session if backend explicitly rejected with 401/403
                    if (e?.response?.status === 401 || e?.response?.status === 403) {
                        console.warn("Session verification rejected (401/403), clearing auth state");
                        clearAuthSession();
                        setUser(null);
                    } else {
                        // Transient network issue or backend starting up - retain cached user
                        console.warn("Backend not yet responding to /me; retaining cached session");
                    }
                }
            }
            setIsLoading(false);
        };

        initAuth();
    }, []);

    const login = async (credentials: Record<string, string>) => {
        const data = await authService.login(credentials);
        setSession(data.accessToken, data.refreshToken, data.user);
        redirectBasedOnRole(data.user.role);
    };

    const register = async (userData: Record<string, any>) => {
        const data = await authService.register(userData);
        setSession(data.accessToken, data.refreshToken, data.user);
        redirectBasedOnRole(data.user.role);
    };

    const googleLogin = async (code: string) => {
        const data = await authService.googleLogin(code);
        setSession(data.accessToken, data.refreshToken, data.user);
        redirectBasedOnRole(data.user.role);
    };

    const logout = async () => {
        setUser(null);
        clearAuthSession();
        try {
            await authService.logout();
        } catch {
            // Local cleanup already completed
        }
        router.push("/login");
    };

    // Route guarding
    useEffect(() => {
        if (isLoading) return;

        const isPublicRoute = ["/", "/login", "/register", "/contact", "/blockchain", "/forgot-password"].includes(pathname as string) ||
            pathname?.startsWith("/auth/callback");

        if (!user && !isPublicRoute) {
            router.push("/login");
            return;
        }

        if (user && isPublicRoute && pathname !== "/" && pathname !== "/blockchain" && pathname !== "/contact" && !pathname?.startsWith("/auth/callback")) {
            redirectBasedOnRole(user.role);
            return;
        }

        // Role-based protection logic
        if (user && pathname) {
            if (pathname.startsWith("/admin") && user.role !== "ADMIN") {
                redirectBasedOnRole(user.role);
            } else if (pathname.startsWith("/finance") && user.role !== "FINANCE" && user.role !== "ADMIN") {
                redirectBasedOnRole(user.role);
            } else if (pathname.startsWith("/project-manager") && user.role !== "PROJECT_MANAGER" && user.role !== "ADMIN") {
                redirectBasedOnRole(user.role);
            } else if (pathname.startsWith("/vendor") && user.role !== "VENDOR" && user.role !== "SELLER") {
                redirectBasedOnRole(user.role);
            } else if (pathname.startsWith("/corporate") && user.role !== "CORPORATE" && user.role !== "BUYER") {
                redirectBasedOnRole(user.role);
            }
        }

    }, [user, isLoading, pathname, router, redirectBasedOnRole]);

    return (
        <AuthContext.Provider value={{ user, isLoading, login, register, googleLogin, setSession, logout }}>
            {children}
        </AuthContext.Provider>
    );
};

export const useAuth = () => {
    const context = useContext(AuthContext);
    if (context === undefined) {
        throw new Error("useAuth must be used within an AuthProvider");
    }
    return context;
};
