import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
export default defineConfig({
  plugins: [uni()],
  resolve: { alias: [{ find: /^vue$/, replacement: '/src/vue-uni-shim.js' }] }
});

