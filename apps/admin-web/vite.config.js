import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 与小程序端共用同一份请求错误文案（apps/shared/request-message.js）
    // lunar-javascript：shared/calendar-marks.mjs 在仓库外层，构建时需显式指到本包 node_modules
    alias: {
      "@shared": fileURLToPath(new URL("../shared", import.meta.url)),
      "lunar-javascript": fileURLToPath(
        new URL("./node_modules/lunar-javascript", import.meta.url)
      ),
    },
  },
  server: {
    // 端口在 README 与网关 CORS 里都固定为 5173：不写死会退到默认 5174，
    // 与家庭端 H5 撞端口（一个占 IPv4、一个占 IPv6，排查起来极难）。
    port: 5173,
    strictPort: true,
    host: "127.0.0.1",
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    proxy: {
      "/api": "http://127.0.0.1:8080"
    }
  },
  build: {
    // 供应商与框架代码拆分，降低首屏单包体积，利于缓存命中
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ["vue"],
          "element-plus": ["element-plus"]
        }
      }
    }
  }
});
