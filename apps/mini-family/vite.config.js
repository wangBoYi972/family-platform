import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
import { fileURLToPath, URL } from "node:url";

// @app 指向本应用 src（共享通话组件用它取自身的 request 封装）；
// @shared 指向 apps/shared，通话组件只此一份，改一处两端同时生效。
const appSrc = fileURLToPath(new URL("./src", import.meta.url));
const shared = fileURLToPath(new URL("../shared", import.meta.url));

export default defineConfig({
  // 使用 uni-app 平台运行时提供的 Vue，确保页面和生命周期正常挂载。
  plugins: [uni()],
  resolve: {
    alias: { "@app": appSrc, "@shared": shared }
  },
  server: {
    // 与管理端（5173）错开端口；API 默认指向本机网关 8080（跑 18080 验收环境时改 FAMILY_API_TARGET）
    port: 5174,
    // 固定 IPv4，保证 http://127.0.0.1:5174 与网关代理地址一致。
    host: "127.0.0.1",
    // 共享组件位于本应用根目录之外，开发服务器需显式放行，否则 dev 下报 outside of allow list。
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    proxy: {
      "/api": { target: process.env.FAMILY_API_TARGET || "http://127.0.0.1:8080", changeOrigin: true }
    }
  }
});
