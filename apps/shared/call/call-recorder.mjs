/**
 * 通话录音：把「本地麦克风 + 所有远端音频」混成一路再录。
 *
 * <p>为什么要混远端：纪要需要双方说的话。WebRTC 是点对点直连，服务器手里没有媒体流，
 * 只有本机同时持有自己的麦克风和每个人的远端流，所以录音只能在这里做。
 *
 * <p>为什么分段：语音转写单次有大小与时长上限（约 10MB / 5 分钟），
 * 而 webm 只有第一段带容器头，切出来的碎片无法单独解码。
 * 因此每 3 分钟主动结束一段、重新开一段，得到若干个可以独立转写的完整文件。
 */

/** 按优先级挑一个当前浏览器支持的容器；iOS 只给 mp4，桌面 Chromium 给 webm/opus。 */
export function pickMimeType() {
  if (typeof MediaRecorder === "undefined") return "";
  const candidates = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"];
  const supported = type => {
    try {
      return !MediaRecorder.isTypeSupported || MediaRecorder.isTypeSupported(type);
    } catch {
      return false;
    }
  };
  return candidates.find(supported) || "";
}

/** 由容器类型推上传文件名后缀，文件服务按扩展名判类型，写错会让转写端嗅探失败。 */
export function extensionFor(mime) {
  const text = String(mime || "");
  if (text.includes("mp4")) return "m4a";
  if (text.includes("ogg")) return "ogg";
  if (text.includes("mpeg")) return "mp3";
  if (text.includes("wav")) return "wav";
  return "webm";
}

export class CallRecorder {
  constructor({ segmentMs = 180000 } = {}) {
    // 低于 30 秒的片段转写开销比收益大，兜住配置错误
    this.segmentMs = Math.max(30000, Number(segmentMs) || 180000);
    this.ctx = null;
    this.dest = null;
    this.sources = new Map();
    this.segments = [];
    this.current = null;
    this.timer = null;
    this.active = false;
    this.mime = "";
  }

  get recording() {
    return this.active;
  }

  async start(localStream) {
    if (this.active) return;
    const Ctx =
      typeof AudioContext !== "undefined"
        ? AudioContext
        : typeof webkitAudioContext !== "undefined"
          ? webkitAudioContext
          : null;
    if (!Ctx) throw new Error("当前环境不支持通话录音（缺少 Web Audio）");
    if (typeof MediaRecorder === "undefined") throw new Error("当前环境不支持通话录音（缺少 MediaRecorder）");

    this.ctx = new Ctx();
    // iOS Safari 在用户手势之外创建的 AudioContext 是 suspended 的，必须显式唤醒
    if (this.ctx.state === "suspended") await this.ctx.resume().catch(() => {});
    this.dest = this.ctx.createMediaStreamDestination();
    this.mime = pickMimeType();
    this.segments = [];
    this.active = true;
    if (localStream) this.addStream("self", localStream);
    this.rotate();
  }

  /** 远端流到达或本地流就绪后接入混音；重复调用是幂等的。 */
  addStream(id, stream) {
    if (!this.active || !this.ctx || !stream) return;
    const key = String(id);
    if (this.sources.has(key)) return;
    try {
      const source = this.ctx.createMediaStreamSource(stream);
      source.connect(this.dest);
      this.sources.set(key, source);
    } catch {
      // 对端刚挂断时音轨可能已结束，忽略即可
    }
  }

  removeStream(id) {
    const key = String(id);
    const source = this.sources.get(key);
    if (!source) return;
    try {
      source.disconnect();
    } catch {
      // 已断开
    }
    this.sources.delete(key);
  }

  rotate() {
    if (!this.active || !this.dest) return;
    let recorder;
    const options = { audioBitsPerSecond: 32000 };
    if (this.mime) options.mimeType = this.mime;
    try {
      recorder = new MediaRecorder(this.dest.stream, options);
    } catch {
      // 部分环境拒绝 mimeType 参数，退回默认配置
      recorder = new MediaRecorder(this.dest.stream);
    }
    const state = { recorder, chunks: [], startedAt: Date.now(), done: null };
    recorder.ondataavailable = event => {
      if (event.data && event.data.size) state.chunks.push(event.data);
    };
    recorder.onstop = () => {
      if (state.chunks.length) {
        this.segments.push({
          blob: new Blob(state.chunks, { type: recorder.mimeType || this.mime || "audio/webm" }),
          durationMs: Date.now() - state.startedAt
        });
      }
      if (state.done) state.done();
    };
    this.current = state;
    recorder.start();
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.next(), this.segmentMs);
  }

  stopCurrent() {
    const state = this.current;
    this.current = null;
    if (!state) return Promise.resolve();
    if (state.recorder.state === "inactive") return Promise.resolve();
    return new Promise(resolve => {
      state.done = resolve;
      try {
        state.recorder.stop();
      } catch {
        resolve();
      }
    });
  }

  async next() {
    if (!this.active) return;
    await this.stopCurrent();
    if (this.active) this.rotate();
  }

  /** 结束录音，返回全部分段（按录制顺序）。 */
  async stop() {
    if (!this.active) return this.segments.slice();
    this.active = false;
    clearTimeout(this.timer);
    this.timer = null;
    await this.stopCurrent();
    this.sources.forEach(source => {
      try {
        source.disconnect();
      } catch {
        // 已断开
      }
    });
    this.sources.clear();
    try {
      await this.ctx?.close();
    } catch {
      // 已关闭
    }
    this.ctx = null;
    this.dest = null;
    return this.segments.slice();
  }
}
