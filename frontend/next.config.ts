import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  distDir: process.env.FINX_NEXT_DIST_DIR || ".next",
  async rewrites() {
    const backendBase =
      process.env.NEXT_PUBLIC_API_URL?.replace(/\/api\/?$/, "") ||
      "https://finx-backend-vq5b.onrender.com";
    return [
      {
        source: "/api/:path*",
        destination: `${backendBase}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
