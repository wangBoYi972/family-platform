/**
 * LiveKit 媒体层适配器（SFU 模式）。
 *
 * 与 webrtc-mesh.js 的 MeshRtc 保持相近的调用面：openLocal / setMic / setCam /
 * close / onStream，这样 CallPanel 只需要一处 feature flag 分支就能双栈切换。
 *
 * 关键差异：本适配器只在 join 时连接一次 SFU，每端仅 1 路上行；
 * 远端轨道由 SDK 自动订阅，通过 onStream(userId, MediaStream) 回调交给 UI 与录音器。
 *
 * SDK 采用动态 import：未启用 SFU、或打包环境尚未安装 livekit-client 时，
 * mesh 链路完全不受影响（不会因模块解析失败而白屏）。
 */
let sdkPromise = null;
function loadSdk() {
  if (!sdkPromise) sdkPromise = import("livekit-client");
  return sdkPromise;
}

export class LiveKitMedia {
  constructor({ kind = "VIDEO", selfId = "", onStream, onPeerState, onDisconnected } = {}) {
    this.kind = kind;
    this.selfId = String(selfId);
    this.onStream = onStream || (() => {});
    this.onPeerState = onPeerState || (() => {});
    this.onDisconnected = onDisconnected || (() => {});
    this.room = null;
    this.local = null;
    this.audioTrack = null;
    this.videoTrack = null;
    this.closed = false;
  }

  /** 采集本地轨道并挂上事件监听；此时尚未连接房间，可在拨号阶段就显示本地预览。 */
  async openLocal() {
    if (this.room) return this.local;
    const { Room, RoomEvent, createLocalAudioTrack, createLocalVideoTrack } = await loadSdk();
    if (this.closed) throw new Error("媒体适配器已关闭");

    this.room = new Room({ adaptiveStream: true, dynacast: true, autoSubscribe: true });

    // identity 由服务端签发为 userId，因此这里拿到的 id 可直接作为 streams / tiles 的 key。
    this.room.on(RoomEvent.TrackSubscribed, (track, _publication, participant) => {
      const id = String(participant?.identity || "");
      if (!id || !track?.mediaStreamTrack) return;
      this.onStream(id, new MediaStream([track.mediaStreamTrack]));
    });
    this.room.on(RoomEvent.TrackUnsubscribed, (track, _publication, participant) => {
      const id = String(participant?.identity || "");
      if (id) this.onStream(id, null);
    });
    this.room.on(RoomEvent.ParticipantConnected, p => this.onPeerState(String(p.identity), "connected"));
    this.room.on(RoomEvent.ParticipantDisconnected, p => this.onPeerState(String(p.identity), "disconnected"));
    this.room.on(RoomEvent.Disconnected, () => this.onDisconnected());

    this.audioTrack = await createLocalAudioTrack({
      echoCancellation: true,
      noiseSuppression: true
    });
    const tracks = [this.audioTrack.mediaStreamTrack];
    if (this.kind === "VIDEO") {
      this.videoTrack = await createLocalVideoTrack({ facingMode: "user" });
      tracks.push(this.videoTrack.mediaStreamTrack);
    }
    this.local = new MediaStream(tracks);
    return this.local;
  }

  /** 拿到服务端签发的 token 后连接房间并发布本地轨道。同一实例只允许连接一次。 */
  async join({ url, token }) {
    if (!this.room) await this.openLocal();
    if (!url || !token) throw new Error("缺少 LiveKit 连接地址或凭证");
    if (this.room.state === "connected") return;

    await this.room.connect(url, token);
    await this.room.localParticipant.publishTrack(this.audioTrack);
    if (this.videoTrack) await this.room.localParticipant.publishTrack(this.videoTrack);
  }

  setMic(on) {
    try {
      this.audioTrack?.setEnabled(on);
    } catch (error) {
      console.warn("[call] 切换麦克风失败：" + (error?.message || error));
    }
  }

  setCam(on) {
    try {
      this.videoTrack?.setEnabled(on);
    } catch (error) {
      console.warn("[call] 切换摄像头失败：" + (error?.message || error));
    }
  }

  /** 翻转摄像头：替换已发布的视频轨，保持本地预览与上行一致。 */
  async replaceVideoTrack(nextTrack) {
    const { LocalVideoTrack } = await loadSdk();
    const replacement = new LocalVideoTrack(nextTrack);
    const previous = this.videoTrack;
    try {
      if (previous) await this.room?.localParticipant.unpublishTrack(previous);
    } catch (error) {
      console.warn("[call] 撤销旧视频轨失败：" + (error?.message || error));
    }
    previous?.stop?.();
    this.videoTrack = replacement;
    if (this.room?.localParticipant) await this.room.localParticipant.publishTrack(replacement);
    this.local = new MediaStream(
      [this.audioTrack?.mediaStreamTrack, replacement.mediaStreamTrack].filter(Boolean)
    );
    return this.local;
  }

  /** 供录音器扩展使用：常规链路通过 onStream 已能拿到所有远端流。 */
  remoteAudioTracks() {
    const out = [];
    this.room?.remoteParticipants.forEach(participant => {
      participant.audioTracks.forEach(publication => {
        if (publication.audioTrack)
          out.push({ id: String(participant.identity), track: publication.audioTrack });
      });
    });
    return out;
  }

  async close() {
    this.closed = true;
    try {
      await this.room?.disconnect();
    } catch {
      // 断网时 disconnect 可能失败，本地资源仍要释放
    }
    try {
      this.audioTrack?.stop();
    } catch {}
    try {
      this.videoTrack?.stop();
    } catch {}
    this.room = null;
    this.local = null;
    this.audioTrack = null;
    this.videoTrack = null;
  }
}
