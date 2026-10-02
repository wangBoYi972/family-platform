// 兼容当前 uni-app 编译器引用的内部 SSR 标记。
export * from '@vue/runtime-dom';
export const isInSSRComponentSetup = false;
export function injectHook(_type, hook) {
  return hook;
}
