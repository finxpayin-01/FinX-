"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { User, Role } from "@/types";
import { authService } from "@/services/auth.service";
import { useRouter, usePathname } from "next/navigation";

interface AuthContextType {
    user: User | null;
    isLoading: boolean;
    login: (data: Record<string, string>) => Promise<void>;
    register: (data: Record<string, any>) => Promise<void>;
    googleLogin: (code: string) => Promise<void>;
    setSession: (accessToken: string, refreshToken: string, user: User) => void;
    logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
    const [user, setUser] = useState<User | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();
    const pathname = usePathname();

    const redirectBasedOnRole = useCallback((role: Role) => {
        let target = "/corporate";
        if (role === "ADMIN") target = "/admin";
        else if (role === "FINANCE") target = "/finance";
        else if (role === "PROJECT_MANAGER") target = "/project-manager";
        else if (role === "VENDOR" || role === "SELLER") target = "/vendor";
        
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
            const token = localStorage.getItem("accessToken");
            const storedUser = localStorage.getItem("user");

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
                } catch (e) {
                    console.warn("Session verification failed, clearing auth state");
                    localStorage.removeItem("accessToken");
                    localStorage.removeItem("refreshToken");
                    localStorage.removeItem("user");
                    setUser(null);
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

    const logout = () => {
        authService.logout();
        setUser(null);
        router.push("/login");
    };

    // Route guarding
    useEffect(() => {
        if (isLoading) return;

        const isPublicRoute = ["/", "/login", "/register"].includes(pathname as string) ||
            pathname?.startsWith("/auth/callback");

        if (!user && !isPublicRoute) {
            router.push("/login");
            return;
        }

        if (user && isPublicRoute && pathname !== "/" && !pathname?.startsWith("/auth/callback")) {
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
