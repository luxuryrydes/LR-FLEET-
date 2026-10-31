/// <reference types="vitest" />
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

declare const process: { env: Record<string, string | undefined> };

/**
 * The developer server is for this PC only (it has known file-access weaknesses and does not prepare logins for a public
 * address). Requests that arrive through Cloudflare get a page explaining how to publish the live site instead.
 */
const blockTunnelOnDevServer: Plugin = {
  name: "lr-fleet-block-tunnel-on-dev-server",
  configureServer(server) {
    server.middlewares.use((req, res, next) => {
      const headers = (req as unknown as { headers: Record<string, unknown> }).headers;
      if (!headers["cf-connecting-ip"] && !headers["cf-ray"]) return next();
      res.statusCode = 403;
      res.setHeader("Content-Type", "text/html; charset=utf-8");
      res.end(`<!doctype html><meta charset="utf-8"><title>LR Fleet Manager</title>
<body style="font-family:Segoe UI,system-ui,sans-serif;max-width:640px;margin:12vh auto;padding:0 20px;color:#0f1b2d">
<h1>Almost there</h1><p>This address is connected to the <b>developer</b> website of LR Fleet Manager, which must not be used online.</p>
<ol><li>On the office PC, close the run-local windows and start <b>start-live.cmd</b>.</li>
<li>In Cloudflare (Zero Trust &rarr; Networks &rarr; Tunnels &rarr; your tunnel &rarr; Public hostname), set the service to <b>HTTP</b> <b>localhost:4173</b>.</li></ol>
<p>Then reload this page.</p></body>`);
    });
  },
};

export default defineConfig({
  plugins: [react(), blockTunnelOnDevServer],
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
