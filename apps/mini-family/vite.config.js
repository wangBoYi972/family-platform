import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
export default defineConfig({
  // 使用 uni-app 平台运行时提供的 Vue，确保页面和生命周期正常挂载。
  plugins: [uni()],
  server: {
    // 与管理端错开端口；API 默认指向本机网关 8080（跑 18080 验收环境时改 FAMILY_API_TARGET）
    port: 5174,
    proxy: {
      "/api": { target: process.env.FAMILY_API_TARGET || "http://127.0.0.1:8080", changeOrigin: true }
    }
  }
});

