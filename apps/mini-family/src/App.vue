<script setup>
import { onLaunch } from "@dcloudio/uni-app";

// #ifdef H5
// 发行构建里，普通 .js 模块拿到的全局 uni 是残缺占位对象（编译器只替换 uni.xxx 成员访问），
// 这里在它缺接口时补一份；开发构建里 uni 本来就是完整的，绝不能覆盖——
// 否则补进去的 `uni.getStorageSync` 会指回覆盖后的对象，自己调自己导致栈溢出。
const nativeUni = globalThis.uni || {};
if (typeof nativeUni.getStorageSync !== "function") {
  globalThis.uni = {
    ...nativeUni,
    getStorageSync: key => uni.getStorageSync(key),
    setStorageSync: (key, value) => uni.setStorageSync(key, value),
    removeStorageSync: key => uni.removeStorageSync(key),
    request: options => uni.request(options),
    uploadFile: options => uni.uploadFile(options),
    downloadFile: options => uni.downloadFile(options),
    connectSocket: options => uni.connectSocket(options),
    showModal: options => uni.showModal(options),
    showToast: options => uni.showToast(options)
  };
}
// #endif
onLaunch(() => {});
</script>

<style>
/* 四个前端共用同一份设计语言：变量、卡片、按钮、动效都来自这里，不再各应用各写一套。 */
@import "./styles/theme.css";
</style>
