/**
 * 通话信令客户端。
 *
 * 认证不把 JWT 放进 URL，而是先用 JWT 换一次性 ticket（30 秒、用后即焚）再握手。
 * 断线自动重连，重连期间信令进入队列，服务端有 30 秒宽限不会误挂断。
 */
import { request } from "@app/utils/request";

const API_BASE = (import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080/api").replace(/\/+$/, "");
const ORIGIN = API_BASE.replace(/\/api$/, "");

function socketUrl(ticket) {
  return ORIGIN.replace(/^http/, "ws") + "/ws/call?ticket=" + encodeURIComponent(ticket);
}

export class CallClient {
  constructor({ onEvent, onStatus } = {}) {
    this.onEvent = onEvent || (() => {});
    this.onStatus = onStatus || (() => {});
    this.task = null;
    this.open = false;
    this.closedByUser = false;
    this.retry = 0;
    this.queue = [];
    this.heartbeat = null;
    this.reconnectTimer = null;
  }

  async connect() {
    if (this.open) return;
    this.closedByUser = false;
    const { ticket } = await request("/media/ws-ticket", {
      method: "POST",
      header: { "Content-Type": "application/json" }
    });
    this.onStatus("connecting");

    const task = uni.connectSocket({ url: socketUrl(ticket), complete: () => {} });
    this.task = task;
    task.onOpen(() => {
      this.open = true;
      this.retry = 0;
      this.onStatus("open");
      this.flush();
      this.startHeartbeat();
    });
    task.onMessage(message => {
      let payload;
      try {
        payload = JSON.parse(message.data);
      } catch {
        return;
      }
      if (!payload || !payload.type || payload.type === "pong") return;
      this.onEvent(payload);
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
    const delay = Math.min(15000, 1000 * Math.pow(2, this.retry++));
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.connect().catch(() => this.handleClose());
    }, wasOpen ? 1200 : delay);
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeat = setInterval(() => this.send({ type: "ping" }), 20000);
  }

  stopHeartbeat() {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
  }

  send(payload) {
    if (!this.open || !this.task) {
      if (this.queue.length < 80) this.queue.push(payload);
      return;
    }
    try {
      this.task.send({ data: JSON.stringify(payload) });
    } catch {
      this.queue.push(payload);
    }
  }

  flush() {
    const pending = this.queue.splice(0);
    pending.forEach(payload => this.send(payload));
  }

  close() {
    this.closedByUser = true;
    this.stopHeartbeat();
    clearTimeout(this.reconnectTimer);
    try {
      this.task?.close({});
    } catch {
      // 已经关闭
    }
    this.task = null;
    this.open = false;
  }
}
