import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
import { fileURLToPath, URL } from "node:url";

// 与另外三端共用请求错误文案（apps/shared/request-message.js）。
// 说明：这里曾经有一条 { find: /^vue$/, replacement: "/src/vue-uni-shim.js" } 的别名，
// 用来补 isInSSRComponentSetup / injectHook。真正的病因是 package.json 缺 @dcloudio/uni-h5
// 且 uni-app 装的是 alpha 版本，导致 H5 只产出空壳（打开白屏）。补齐依赖后别名不再需要，
// 与家庭端、移动端保持同一套配置。
const shared = fileURLToPath(new URL("../shared", import.meta.url));

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: [{ find: "@shared", replacement: shared }]
  },
  server: {
    // 5173 管理端 / 5174 家庭端 / 5175 移动端，档案端固定 5176
    port: 5176,
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] }
  }
});
