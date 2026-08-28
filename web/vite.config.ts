import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const devApiOrigin = process.env.VITE_DEV_API_ORIGIN ?? "https://localhost";
const stripApiPrefix = process.env.VITE_DEV_API_STRIP_PREFIX === "true";

const proxy: {
    target: string;
    changeOrigin: boolean;
    secure: boolean;
    rewrite?: (path: string) => string;
  } = {
    target: devApiOrigin,
    changeOrigin: true,
    secure: false,
  };
  if (stripApiPrefix) {
    proxy.rewrite = (path) => path.replace(/^\/api/, "");
  }

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": proxy,
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    restoreMocks: true,
  },
});
