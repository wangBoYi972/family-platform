import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
import { fileURLToPath, URL } from "node:url";

// 与家庭端共用 apps/shared 下的通话组件；@app 指向本应用 src。
const appSrc = fileURLToPath(new URL("./src", import.meta.url));
const shared = fileURLToPath(new URL("../shared", import.meta.url));

export default defineConfig({
  // 使用 uni-app 平台运行时提供的 Vue，确保页面和生命周期正常挂载。
  plugins: [uni()],
  resolve: {
    // shared/ 在应用包外，动态 import("livekit-client") 需显式指到本应用 node_modules
    alias: {
      "@app": appSrc,
      "@shared": shared,
      "livekit-client": fileURLToPath(new URL("./node_modules/livekit-client", import.meta.url)),
      "lunar-javascript": fileURLToPath(new URL("./node_modules/lunar-javascript", import.meta.url)),
    },
  },
  server: {
    // 默认 5173 会与管理端撞端口，这里固定 5175（网关 CORS 已放行）。
    port: 5175,
    // 固定 IPv4，保证 http://127.0.0.1:5175 与网关代理地址一致。
    host: "127.0.0.1",
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    proxy: {
      "/api": { target: process.env.FAMILY_API_TARGET || "http://127.0.0.1:8080", changeOrigin: true }
    }
  }
});
