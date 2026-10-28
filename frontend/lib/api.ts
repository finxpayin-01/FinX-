import axios from "axios";
import { AuthResponse } from "@/types";

/**
 * Dynamically resolves the API base URL.
 * In a browser running on a public domain (e.g. finx-frontend.onrender.com),
 * it ensures we NEVER accidentally call localhost:8080.
 */
export const getApiBaseUrl = (): string => {
    let url = process.env.NEXT_PUBLIC_API_URL;

    if (!url || url.trim() === "") {
        if (typeof window !== "undefined") {
            const isLocal =
                window.location.hostname === "localhost" ||
                window.location.hostname === "127.0.0.1";
            if (isLocal) {
                url = "http://localhost:8080/api";
            } else {
                url = `${window.location.origin}/api`;
            }
        } else {
            url = "http://localhost:8080/api";
        }
    }

    url = url.trim().replace(/\/+$/, "");
    if (!url.endsWith("/api")) {
        url = `${url}/api`;
    }

    return url;
};

export const api = axios.create({
    baseURL: getApiBaseUrl(),
    timeout: 25000,
    headers: {
        "Content-Type": "application/json",
    },
});

// Helper to get token
export const getAccessToken = () => {
    if (typeof window !== "undefined") {
        return localStorage.getItem("accessToken");
    }
    return null;
};

// Helper to get refresh token
export const getRefreshToken = () => {
    if (typeof window !== "undefined") {
        return localStorage.getItem("refreshToken");
    }
    return null;
};

// Clear session helper
export const clearAuthSession = () => {
    if (typeof window !== "undefined") {
        localStorage.removeItem("accessToken");
        localStorage.removeItem("refreshToken");
        localStorage.removeItem("user");
    }
};

// Request interceptor to attach token, dynamic baseURL, and normalize double prefixes
api.interceptors.request.use(
    (config) => {
        config.baseURL = getApiBaseUrl();

        // Prevent double prefix hazards: e.g. /api/deals or /api/v1/...
        if (config.url && config.url.startsWith("/api/")) {
            config.url = config.url.replace(/^\/api/, "");
        }

        const token = getAccessToken();
        if (token && config.headers) {
            config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

// Response interceptor for token refresh
api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;
        const requestUrl = originalRequest?.url || "";

        // NEVER attempt refresh on authentication endpoints (login, register, refresh)
        const isAuthEndpoint =
            requestUrl.includes("/auth/login") ||
            requestUrl.includes("/auth/register") ||
            requestUrl.includes("/auth/refresh");

        if (error.response?.status === 401 && !originalRequest?._retry && !isAuthEndpoint) {
            originalRequest._retry = true;
            try {
                const refreshToken = getRefreshToken();
                if (!refreshToken) {
                    throw new Error("No refresh token");
                }

                const currentApiUrl = getApiBaseUrl();
                const res = await axios.post(`${currentApiUrl}/auth/refresh`, {
                    refreshToken,
                });

                const payload = res.data?.data || res.data;
                const newAccessToken = payload?.accessToken;
                const newRefreshToken = payload?.refreshToken;

                if (!newAccessToken) {
                    throw new Error("Invalid token refresh payload");
                }

                localStorage.setItem("accessToken", newAccessToken);
                if (newRefreshToken) {
                    localStorage.setItem("refreshToken", newRefreshToken);
                }

                originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
                return api(originalRequest);
            } catch (err) {
                // Refresh failed, logout user cleanly
                clearAuthSession();
                if (typeof window !== "undefined") {
                    window.location.href = "/login";
                }
                return Promise.reject(err);
            }
        }
        return Promise.reject(error);
    }
);
