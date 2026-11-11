/**
 * 刷新闸门：把一串突发事件合并成一次刷新。
 *
 * 一次相册上传会产生 file.created、file.linked、album.updated 三条以上事件，
 * 每条都立刻重拉列表既浪费流量，也会让界面连续闪动。这里在窗口期内只保留最后一次。
 *
 * push 可附带事件类型（如 "task.created"）：窗口期内收集所有类型，flush 时以 Set
 * 交给 onFlush，宿主据此只重拉受影响的面板；没带类型的 push 得到空集合，语义是
 * 「不知道哪里变了」，宿主应全量刷新兜底。
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
  let topics = new Set();

  function fire(force) {
    timer = null;
    const count = pending;
    const events = topics;
    pending = 0;
    topics = new Set();
    if (count || force) onFlush(count, events);
  }

  return {
    /** 记一次「有变化」，窗口期结束时统一回调；event 是可选的事件类型。 */
    push(event) {
      pending += 1;
      if (event) topics.add(event);
      if (timer) cancel(timer);
      timer = schedule(fire, delay);
    },
    /** 立刻兑现（例如标签页重新可见或断线重连时），不等窗口期；即使无积压也强制触发一次全量刷新。 */
    flushNow() {
      if (timer) cancel(timer);
      fire(true);
    },
    dispose() {
      if (timer) cancel(timer);
      timer = null;
      pending = 0;
      topics = new Set();
    },
    get pending() {
      return pending;
    }
  };
}
