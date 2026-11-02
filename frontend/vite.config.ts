/// <reference types="vitest" />
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

declare const process: { env: Record<string, string | undefined> };

/**
 * The developer server is for this PC only (it has known file-access weaknesses and does not prepare logins for a public
 * address). Requests that arrive through Cloudflare get a page explaining how to publish the live site instead.
 */
type Use = (fn: (req: unknown, res: { statusCode: number; setHeader(k: string, v: string): void; end(s: string): void }, next: () => void) => void) => void;

function refuseTunnel(use: Use, html: string) {
  use((req, res, next) => {
    const headers = (req as { headers: Record<string, unknown> }).headers;
    if (!headers["cf-connecting-ip"] && !headers["cf-ray"]) return next();
    res.statusCode = 403;
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.end(`<!doctype html><meta charset="utf-8"><title>LR Fleet Manager</title>
<body style="font-family:Segoe UI,system-ui,sans-serif;max-width:640px;margin:12vh auto;padding:0 20px;color:#0f1b2d">${html}</body>`);
  });
}

const blockTunnelOnDevServer: Plugin = {
  name: "lr-fleet-block-tunnel-on-dev-server",
  configureServer(server) {
    refuseTunnel(server.middlewares.use.bind(server.middlewares) as unknown as Use, `
<h1>Almost there</h1><p>This address is connected to the <b>developer</b> website of LR Fleet Manager, which must not be used online.</p>
<ol><li>On the office PC, close the dev-local windows and start <b>run-local.cmd</b>.</li>
<li>In Cloudflare (Zero Trust &rarr; Networks &rarr; Tunnels &rarr; your tunnel &rarr; Routes), set the service to <b>http://localhost:4173</b>.</li></ol>
<p>Then reload this page.</p>`);
  },
};

/** run-local.cmd sets LRFLEET_LOCAL_ONLY while an administrator still has a published default password. */
const blockTunnelWhileUnsafe: Plugin = {
  name: "lr-fleet-block-tunnel-while-unsafe",
  configurePreviewServer(server) {
    if (process.env.LRFLEET_LOCAL_ONLY !== "1") return;
    refuseTunnel(server.middlewares.use.bind(server.middlewares) as unknown as Use, `
<h1>Not open yet</h1><p>LR Fleet Manager is running, but an administrator still has a default password, so it is not
available online. On the office PC open <b>http://localhost:4173</b>, go to <i>Users &amp; roles</i>, set a strong password,
then start <b>run-local.cmd</b> again.</p>`);
  },
};

export default defineConfig({
  plugins: [react(), blockTunnelOnDevServer, blockTunnelWhileUnsafe],
  server: {
    port: 5173,
    // In development the API runs on :8080; production uses VITE_API_BASE_URL.
    proxy: { "/api": "http://localhost:8080" },
    fs: { allow: [".."] },
  },
  // Published through Cloudflare Tunnel (run-local.cmd): the built site, with /api passed to the local API. The browser's
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
