import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
export default defineConfig({
  // 使用 uni-app 平台运行时提供的 Vue，确保页面和生命周期正常挂载。
  plugins: [uni()]
});

