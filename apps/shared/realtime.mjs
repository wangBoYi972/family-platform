/**
 * 刷新闸门：把一串突发事件合并成一次刷新。
 *
 * 一次相册上传会产生 file.created、file.linked、album.updated 三条以上事件，
 * 每条都立刻重拉列表既浪费流量，也会让界面连续闪动。这里在窗口期内只保留最后一次。
 *
 * 定时器通过参数注入，单测里可以直接手动触发，不依赖真实时间。
 */
export function createRefreshGate({
  delay = 400,
  onFlush = () => {},
  schedule = (fn, ms) => setTimeout(fn, ms),
  cancel = id => clearTimeout(id)
} = {}) {
  let timer = null;
  let pending = 0;

  function fire() {
    timer = null;
    const count = pending;
    pending = 0;
    if (count) onFlush(count);
  }

  return {
    /** 记一次「有变化」，窗口期结束时统一回调。 */
    push() {
      pending += 1;
      if (timer) cancel(timer);
      timer = schedule(fire, delay);
    },
    /** 立刻兑现（例如标签页重新可见时），不等窗口期。 */
    flushNow() {
      if (timer) cancel(timer);
      fire();
    },
    dispose() {
      if (timer) cancel(timer);
      timer = null;
      pending = 0;
    },
    get pending() {
      return pending;
    }
  };
}
