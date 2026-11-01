/**
 * 家庭实时通道。
 *
 * 后端 im-svc 已经订阅了全部领域事件（任务、公告、动态、相册、文件、档案），
 * 收到后向同家庭的在线连接广播 {"type":"refresh"}；这里只负责把浏览器挂上去。
 *
 * 和其它推送分开的原因：通话要用 `/ws/call`（媒体服务，带信令语义、30 秒宽限），
 * 这里用 `/ws/im`，只承载「家里有变化，拉一下最新数据」。
 *
 * 认证同通话：JWT 不放进 URL，先用 JWT 换一次性 ticket 再握手。
 */
import { request } from "@app/utils/request";
import { createRefreshGate } from "./realtime.mjs";

const API_BASE = (import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080/api").replace(/\/+$/, "");
const ORIGIN = API_BASE.replace(/\/api$/, "");
const HEARTBEAT_MS = 25000;
const MAX_RETRY_MS = 20000;

function socketUrl(ticket) {
  return ORIGIN.replace(/^http/, "ws") + "/ws/im?ticket=" + encodeURIComponent(ticket);
}

export class FamilySocket {
  constructor({ onRefresh, onStatus, gate } = {}) {
    this.onRefresh = onRefresh || (() => {});
    this.onStatus = onStatus || (() => {});
    this.gate = gate || createRefreshGate({ delay: 400, onFlush: () => this.onRefresh() });
    this.task = null;
    this.open = false;
    this.closedByUser = false;
    this.retry = 0;
    this.heartbeat = null;
    this.reconnectTimer = null;
  }

  async connect() {
    if (this.open) return;
    // 显式 connect 视为用户重新登录后重连，清掉上一次的主动断开标记
    this.closedByUser = false;
    // 连之前先把话说完：并发的 connect() 会各自换一张 ticket，多开一条连接。
    if (this.connecting) return this.connecting;
    this.connecting = this.openSocket().finally(() => { this.connecting = null; });
    return this.connecting;
  }

  async openSocket() {
    let ticket;
    try {
      ({ ticket } = await request("/im/ws-ticket", { method: "POST", header: { "Content-Type": "application/json" } }));
    } catch (error) {
      // 凭证失效不重连：重连只会继续 401，等用户重新登录
      if (error?.status === 401) return this.close();
      return this.retryLater();
    }
    this.onStatus("connecting");
    const task = uni.connectSocket({ url: socketUrl(ticket), complete: () => {} });
    this.task = task;
    task.onOpen(() => {
      this.open = true;
      this.retry = 0;
      this.onStatus("open");
      this.startHeartbeat();
      // 断线期间错过的变化，重连后补一次
      this.gate.flushNow();
    });
    task.onMessage(message => {
      let payload;
      try {
        payload = JSON.parse(message.data);
      } catch {
        return;
      }
      if (payload?.type === "refresh") this.gate.push();
    });
    task.onClose(() => this.handleClose());
    task.onError(() => this.handleClose());
  }

  handleClose() {
    const wasOpen = this.open;
    this.open = false;
    this.stopHeartbeat();
    this.onStatus("closed");
    if (this.closedByUser) return;
    // 刚还连着就断，多半是网络瞬抖，1 秒后重试；从未连上才走指数退避
    this.retryLater(wasOpen ? 1200 : undefined);
  }

  retryLater(fixedDelay) {
    const delay = fixedDelay ?? Math.min(MAX_RETRY_MS, 1000 * Math.pow(2, this.retry++));
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.openSocket().catch(() => this.handleClose());
    }, delay);
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeat = setInterval(() => {
      try {
        this.task?.send({ data: JSON.stringify({ type: "ping" }) });
      } catch {
        // 发送失败交给 onError/onClose 处理，这里不重连
      }
    }, HEARTBEAT_MS);
  }

  stopHeartbeat() {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
  }

  close() {
    this.closedByUser = true;
    this.stopHeartbeat();
    clearTimeout(this.reconnectTimer);
    this.gate.dispose();
    try {
      this.task?.close({});
    } catch {
      // 已经关闭
    }
    this.task = null;
    this.open = false;
  }
}
