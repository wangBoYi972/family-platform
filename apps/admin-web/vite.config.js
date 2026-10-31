import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 与小程序端共用同一份请求错误文案（apps/shared/request-message.js）
    alias: { "@shared": fileURLToPath(new URL("../shared", import.meta.url)) }
  },
  server: {
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
