// H5 构建里，普通 .js 模块中的裸 uni 标识符是个残缺占位对象：
// 编译器只把 uni.xxx 这样的「成员访问」替换成运行时导入，裸引用（如 { uni }）原样保留。
//
// 所以这里把用到的接口逐个写成成员访问，交给编译器处理，再交给 apps/shared 下
// 按参数注入 uni 的模块（请求封装、长连接、AI 流式对话）使用。
// 方法都在调用时才会取到真实实现，不受模块初始化早于运行时就绪的影响。
export const uniApi = {
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
