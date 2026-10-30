/**
 * WebRTC 网状（mesh）连接管理。
 *
 * 1 对 1 只有一条连接；多人时每两人之间一条，人数上限由服务端控制（默认 8）。
 * 防冲突规则：由「接听方」向已在房间的成员发 offer，发起方只应答，避免双向同时 offer。
 */
export class MeshRtc {
  constructor({ iceServers, kind, selfId, send, onStream, onPeerState }) {
    this.iceServers = iceServers || [];
    this.kind = kind || "VIDEO";
    this.selfId = String(selfId);
    this.send = send;
    this.onStream = onStream || (() => {});
    this.onPeerState = onPeerState || (() => {});
    this.local = null;
    this.peers = new Map();
    this.pendingIce = new Map();
  }

  async openLocal() {
    if (this.local) return this.local;
    if (!navigator?.mediaDevices?.getUserMedia) throw new Error("当前环境不支持实时音视频（需要 HTTPS 或 localhost）");
    this.local = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video:
        this.kind === "VIDEO"
          ? { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 24, max: 30 }, facingMode: "user" }
          : false
    });
    return this.local;
  }

  ensure(userId) {
    const key = String(userId);
    const existing = this.peers.get(key);
    if (existing) return existing;

    const pc = new RTCPeerConnection({
      iceServers: this.iceServers,
      iceCandidatePoolSize: 4,
      bundlePolicy: "max-bundle"
    });
    this.local?.getTracks().forEach(track => pc.addTrack(track, this.local));

    pc.onicecandidate = event => {
      if (!event.candidate) return;
      this.send({ type: "webrtc.ice", to: Number(key), candidate: event.candidate.toJSON?.() || event.candidate });
    };
    pc.ontrack = event => {
      const stream = event.streams?.[0];
      if (stream) this.onStream(key, stream);
    };
    pc.onconnectionstatechange = () => {
      this.onPeerState(key, pc.connectionState);
      if (["failed", "closed"].includes(pc.connectionState)) this.dropPeer(key);
    };

    const peer = { pc, remoteSet: false, offered: false };
    this.peers.set(key, peer);
    return peer;
  }

  async offerTo(userId) {
    const key = String(userId);
    // 没有本地轨道时 createOffer 会产生不含 m-line 的 SDP，max-bundle 会直接报
    // "session description has no BUNDLE group"，对方永远收不到画面。
    if (!this.local) await this.openLocal();
    const peer = this.ensure(key);
    // 同一对端只发一次 offer：接听方既可能在 HTTP 应答后触发，也可能收到服务端的
    // call.accepted 回执再触发一次，重复 offer 会让 signalingState 变成 have-local-offer。
    if (peer.offered || peer.pc.signalingState !== "stable") return;
    peer.offered = true;
    const offer = await peer.pc.createOffer();
    await peer.pc.setLocalDescription(offer);
    this.send({ type: "webrtc.offer", to: Number(key), sdp: peer.pc.localDescription.sdp });
  }

  async handleOffer(from, sdp) {
    const key = String(from);
    const { pc } = this.ensure(key);
    await pc.setRemoteDescription({ type: "offer", sdp });
    this.peers.get(key).remoteSet = true;
    await this.flushIce(key);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    this.send({ type: "webrtc.answer", to: Number(key), sdp: pc.localDescription.sdp });
  }

  async handleAnswer(from, sdp) {
    const key = String(from);
    const peer = this.peers.get(key);
    if (!peer) return;
    if (peer.pc.signalingState === "stable") return; // 重复应答，忽略
    await peer.pc.setRemoteDescription({ type: "answer", sdp });
    peer.remoteSet = true;
    await this.flushIce(key);
  }

  async handleIce(from, candidate) {
    const key = String(from);
    const peer = this.peers.get(key);
    if (!peer) return;
    if (!peer.remoteSet) {
      const queue = this.pendingIce.get(key) || [];
      queue.push(candidate);
      this.pendingIce.set(key, queue);
      return;
    }
    try {
      await peer.pc.addIceCandidate(candidate);
    } catch {
      // 候选过期（网络切换）属正常情况，忽略即可
    }
  }

  async flushIce(key) {
    const queue = this.pendingIce.get(key);
    if (!queue?.length) return;
    this.pendingIce.delete(key);
    for (const candidate of queue) {
      try {
        await this.peers.get(key)?.pc.addIceCandidate(candidate);
      } catch {
        // 同上
      }
    }
  }

  setMic(enabled) {
    this.local?.getAudioTracks().forEach(track => (track.enabled = enabled));
  }

  setCam(enabled) {
    this.local?.getVideoTracks().forEach(track => (track.enabled = enabled));
  }

  /** 前后摄像头切换：替换本地视频轨并同步到所有对端，不重建连接。 */
  async replaceVideoTrack(track) {
    if (!this.local) return null;
    this.local.getVideoTracks().forEach(item => {
      this.local.removeTrack(item);
      item.stop();
    });
    this.local.addTrack(track);
    for (const { pc } of this.peers.values()) {
      const sender = pc.getSenders().find(item => item.track?.kind === "video");
      if (sender) await sender.replaceTrack(track);
    }
    return this.local;
  }

  dropPeer(userId) {
    const key = String(userId);
    const peer = this.peers.get(key);
    if (!peer) return;
    try {
      peer.pc.close();
    } catch {
      // 已关闭
    }
    this.peers.delete(key);
    this.pendingIce.delete(key);
    this.onPeerState(key, "closed");
  }

  close() {
    this.local?.getTracks().forEach(track => track.stop());
    this.local = null;
    Array.from(this.peers.keys()).forEach(key => this.dropPeer(key));
  }
}
