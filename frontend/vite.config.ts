/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

declare const process: { env: Record<string, string | undefined> };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // In development the API runs on :8080; production uses VITE_API_BASE_URL.
    proxy: { "/api": "http://localhost:8080" },
    fs: { allow: [".."] },
  },
  // Shared through Cloudflare Tunnel (share-online.cmd): the built site, with /api passed to the local API. The browser's
  // Origin is dropped so the API sees a same-site call; X-Forwarded-For carries each colleague's IP for rate limits and audit.
  preview: {
    port: 4173,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.LRFLEET_API_URL ?? "http://localhost:8080",
        xfwd: true,
        configure: (proxy) => proxy.on("proxyReq", (req) => req.removeHeader("origin")),
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
