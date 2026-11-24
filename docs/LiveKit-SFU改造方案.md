# LiveKit SFU 改造方案

日期：2026-11-24。
定案：SFU 选型 = **LiveKit 开源自建**（AB 对比见 `docs/正式环境验收执行方案.md` 第 4 节）。
目标：把当前浏览器端 WebRTC mesh（上限 8 人）升级为 SFU 架构，支持 20+ 人家庭多人通话，媒体经自建节点、隐私可控，同时保持现有信令状态机、家庭隔离与录音纪要链路可用。

## 1. 改造原则：最小侵入，媒体层可插拔

现有通话系统由两层组成，改造只动媒体层：

```
┌─────────────────────────── 信令层（保留，几乎不动）───────────────────────────┐
│ CallController / CallService / CallSocket / CallSignalBus                    │
│ 职责：发起·接听·拒接·离开·结束的状态机、家庭隔离、人数上限、断线宽限、录音广播    │
│ 数据表：call_session / call_participant（不变）                              │
└───────────────────────────────────────────────────────────────────────────────┘
                                      │  callId + 参与者名单 + 媒体模式
                                      ▼
┌─────────────────────────── 媒体层（本次替换）───────────────────────────────────┐
│ 旧：webrtc-mesh.js（每两人一条 PeerConnection，N² 上行）                       │
│ 新：livekit-client Room（每端一路上行，SFU 选择性转发）+ 服务端签发 JoinToken    │
└───────────────────────────────────────────────────────────────────────────────┘
```

**关键结论**：`CallSocket` 的 `webrtc.offer/answer/ice` 三种信令在 SFU 模式下不再需要（媒体协商由 LiveKit SDK 自己完成），但来电弹窗、接听、挂断、录音状态广播、断线宽限、历史记录全部复用。这样改动面最小、风险最低，且 mesh 与 SFU 可用 feature flag 灰度切换、随时回滚。

## 2. 目标架构

```
家庭端 H5 / APP
┌────────────────────────────────────────────┐
│ CallPanel（UI 不变）                        │
│  ├ call-client.js  信令 WS（保留）           │──WSS /ws/call──┐
│  └ livekit-client  Room（新增，替换 MeshRtc） │                │
│       只推 1 路上行，订阅 N-1 路下行          │                │
└───────────────┬──────────────────────────────┘                │
                │ WSS 媒体 + 数据                                │
                ▼                                              ▼
        ┌───────────────────┐                    ┌──────────────────────────┐
        │ livekit-server    │                    │ gateway → family-media-svc│
        │ (SFU, host 网络)   │                    │ CallSocket/CallService    │
        │ 50100-50200/udp   │                    │ 状态机·鉴权·录音广播       │
        └─────────┬─────────┘                    └────────────┬─────────────┘
                  │ 上行 Token 签发 REST                       │
                  │ /api/media/calls/{id}/livekit-token        │
                  └────────────────────────────────────────────┘
                                   │
                          MySQL(family_media) + Redis(跨实例)
```

数据流：
1. 用户 A 发起通话（`POST /media/calls`），状态机建 `call_session`，经信令推 `call.invited` 给被叫；
2. 被叫接听（`call.answer`），状态机置 ACTIVE，广播 `call.accepted`；
3. **每个参与者**（含发起者）收到 `call.accepted` 后，调 `POST /media/calls/{id}/livekit-token` 拿到自己的 JoinToken，用 `livekit-client` 连入同一个 LiveKit 房间 `family-<callId>`；
4. 媒体全部由 LiveKit SFU 转发；客户端只推 1 路上行；
5. 挂断/离开仍走原信令，状态机结束会话，LiveKit 房间因空房间超时自动回收（也可由服务端主动删房）。

## 3. 服务端改造

### 3.1 依赖
`backend/family-media-svc/pom.xml` 增加官方 Java 服务端 SDK：
```xml
<dependency>
  <groupId>io.livekit</groupId>
  <artifactId>livekit-server</artifactId>
  <version>0.14.0</version>
</dependency>
```
> 该库提供 `AccessToken`、`VideoGrant`、`RoomServiceClient`、`EgressServiceClient`、`WebhookReceiver`。仅需签 Token 时只依赖轻量部分；如需主动删房/录制，用 `RoomServiceClient`/`EgressServiceClient`。

### 3.2 配置（`application.yml` + 环境变量）
新增 `family.livekit.*`，全部可被环境变量覆盖：
```yaml
family:
  livekit:
    enabled: false          # feature flag：false=走旧 mesh，true=走 SFU（灰度切换）
    api-key: ${LIVEKIT_API_KEY:}
    api-secret: ${LIVEKIT_API_SECRET:}
    public-url: ${LIVEKIT_PUBLIC_URL:}      # wss://livekit.example.com，下发给客户端
    internal-url: ${LIVEKIT_INTERNAL_URL:http://127.0.0.1:7880}
    room-prefix: ${LIVEKIT_ROOM_PREFIX:family-}
    token-ttl-seconds: 3600
```
新增配置类 `LiveKitProperties`（照现有 `MediaProperties` 风格写 `@ConfigurationProperties`）。

### 3.3 JoinToken 签发接口（核心）

新增 `POST /api/media/calls/{id}/livekit-token`，返回 `{ token, url, roomName }`。**鉴权是这个接口的安全命门**：必须确认"当前登录用户确实是这通通话的参与者"，否则任何登录用户都能凭 callId 拿到进房凭证。

实现要点（复用现有 `CallService.detail()` 已做的家庭隔离校验）：
```java
@PostMapping("/calls/{id}/livekit-token")
public ApiResponse<LiveKitTokenVO> liveKitToken(HttpServletRequest request, @PathVariable String id) {
    SessionPrincipal principal = principals.from(request);
    if (!properties.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "SFU 未启用");
    CallVO call = calls.detail(principal, id);           // 内部已校验家庭归属 + 参与者身份
    boolean isParticipant = call.participants().stream()
            .anyMatch(p -> p.userId() == principal.userId()
                    && ("JOINED".equals(p.state()) || "INVITED".equals(p.state())));
    if (!isParticipant) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");

    String room = properties.getRoomPrefix() + id;
    AccessToken token = new AccessToken(properties.getApiKey(), properties.getApiSecret());
    token.setIdentity(String.valueOf(principal.userId()));       // identity = userId，与参与者表对齐
    token.setName(principal.displayName());
    token.setTtl(properties.getTokenTtlSeconds() * 1000L);
    token.addGrants(
        new RoomJoin(true),
        new RoomName(room),
        new CanPublish(true),
        new CanSubscribe(true),
        new CanPublishData(true),
        new RoomCreate(false)                                    // 禁止客户端建房，房间由服务端控制
    );
    return ApiResponse.ok(new LiveKitTokenVO(token.toJwt(), properties.getPublicUrl(), room));
}
```
安全约束：
- `identity` 用服务端会话里的 `userId`，**绝不信任客户端上报的 userId**（与 `CallSocket` 现有原则一致）；
- `RoomCreate(false)` + `livekit.yaml` 的 `room.auto_create: false`：客户端不能自己造房间；
- Token 有效期 1 小时，覆盖单通通话时长；过期需重新签发；
- 若 `call.kind == AUDIO`，仍可 `CanPublish(true)` 但前端只开麦克风轨道；权限在服务端按 `kind` 细化可选。

### 3.4 可选：服务端主动删房
通话结束时使用 `RoomServiceClient.deleteRoom(room)`，避免等待空房间超时：
```java
RoomServiceClient rooms = RoomServiceClient.createClient(internalUrl, apiKey, apiSecret);
rooms.deleteRoom(room).execute();
```
非必须（空房间超时会回收），但能让资源立即释放，建议在 `CallService.end()` 里异步调用，失败只记日志、不影响状态机。

### 3.5 Webhook（可选但推荐）
LiveKit 可通过 webhook 推送 `participant_connected`/`participant_disconnected`/`track_published` 等事件。接入 `WebhookReceiver` 校验签名后，可用于：
- 校准参与者真实进房状态（比信令的 `call.state` 更准）；
- 录制完成事件驱动纪要生成。
第一版可不做，作为 v2 增强。

## 4. 前端改造

### 4.1 依赖
三个 uni-app 前端（mini-family、mobile-app、mini-archive 若涉及）安装：
```bash
npm i livekit-client
```
> 注意 uni-app H5 编译：LiveKit SDK 是标准 ESM，H5 下可直接用；小程序端不使用 WebRTC（详见限制），不引入。

### 4.2 新增 `apps/shared/call/livekit-media.js`
封装 LiveKit 客户端，保持与现有 `MeshRtc` 相近的调用面，降低 CallPanel 改动量：
```js
import { Room, RoomEvent, Track, RemoteParticipant } from "livekit-client";

export class LiveKitMedia {
  constructor({ onStream, onPeerState, onDisconnected }) {
    this.room = new Room({ adaptiveStream: true, dynacast: true });
    this.onStream = onStream;
    this.onPeerState = onPeerState || (() => {});
    this.onDisconnected = onDisconnected || (() => {});
    this.room.on(RoomEvent.TrackSubscribed, (track, pub, participant) => {
      const stream = new MediaStream([track.mediaStreamTrack]);
      this.onStream(participant.identity, stream);   // identity 即 userId，与现有 streams key 对齐
    });
    this.room.on(RoomEvent.TrackUnsubscribed, (track) => {
      const stream = new MediaStream([track.mediaStreamTrack]);
      this.onStream(participant?.identity, null);     // 触发 UI 移除 tile
    });
    this.room.on(RoomEvent.ParticipantConnected, p => this.onPeerState(p.identity, "connected"));
    this.room.on(RoomEvent.Disconnected, () => this.onDisconnected());
  }

  async join({ url, token }) {
    await this.room.connect(url, token);
    await this.room.localParticipant.enableCameraAndMicrophone();
  }
  async setMic(on)  { await this.room.localParticipant.setMicrophoneEnabled(on); }
  async setCam(on)  { await this.room.localParticipant.setCameraEnabled(on); }
  /** 供录音器订阅：本地麦克风轨 + 所有远端音轨 */
  getLocalAudioTrack() { return this.room.localParticipant.audioTracks.values().next().value?.audioTrack; }
  remoteAudioTracks()  {
    const out = [];
    this.room.remoteParticipants.forEach(p =>
      p.audioTracks.forEach(pub => out.push({ id: p.identity, track: pub.audioTrack })));
    return out;
  }
  async close() { await this.room.disconnect(); }
}
```

### 4.3 CallPanel 改动点（对照现有代码）
| 现有位置 | 改动 |
| --- | --- |
| `import { MeshRtc }` | 改为按 `sfuEnabled` 动态引入 `LiveKitMedia`（或两者都保留，feature flag 切换） |
| `prepareMedia()` | SFU 模式：不再请求 `/media/ice-servers`、不 new MeshRtc；改为在接听后调 `/livekit-token` 拿凭证并 `join()` |
| `startMedia(initiator)` | SFU 模式：不再 `mesh.offerTo(person.userId)`；加入房间后 SDK 自动订阅所有人，删掉逐人对端协商 |
| `handleEvent` 的 `webrtc.offer/answer/ice` 分支 | SFU 模式下忽略（服务端也不会再发）；保留 mesh 模式下原有逻辑用于回滚 |
| `onStream(id, stream)` | 保持不变：`streams[id] = stream; recorder?.addStream(id, stream)` —— 这是录音链路的关键契约 |
| 静音/关摄像头 | `mesh` 调用改为 `livekitMedia.setMic/setCam` |
| `resetMedia()` | 增加 `livekitMedia?.close()` |

**feature flag 来源**：`GET /api/media/config` 下发 `{ sfuEnabled: true, publicUrl }`（或复用现有接口加字段）。灰度期间可先对内部账号开启。

### 4.4 录音链路适配（保持纪要可用）
现有 `CallRecorder` 用 Web Audio 把"本地麦克风 + 远端流"混成一路。SFU 模式下远端轨来自 LiveKit：
- `onStream` 回调仍然收到每个 `identity(userId) → MediaStream`，`recorder.addStream(id, stream)` 逻辑不变；
- 需在 `TrackSubscribed` 时把 audio track 包成 `MediaStream` 传给 onStream（见 4.2 代码）；
- 后加入成员的音轨也会触发 `TrackSubscribed`，自动进混音，纪要不会漏话；
- 隐私边界不变：录音仍只在客户端混流、只在用户显式同意后上传（`consent=true` 校验在后端），媒体不落服务器。

> 更优的 v2 方案：用 LiveKit **Egress** 服务端录制（音频到 S3/OSS），音质稳定、不依赖客户端性能。但它会让媒体落到服务端，隐私模型变化，需单独评估；第一版保持客户端录音。

## 5. 迁移、灰度与回滚

1. **并行部署**：先起 LiveKit 服务，配置好 `keys` 与 Nginx WSS 反代；此时不影响现有 mesh 通话。
2. **服务端灰度**：`family.livekit.enabled=false` 默认关闭；对内部测试账号或按家庭白名单开启。
3. **前端双栈**：CallPanel 同时保留 MeshRtc 与 LiveKitMedia，按 `/media/config` 的 `sfuEnabled` 选择；`sfuEnabled=false` 走原 mesh，零风险回滚。
4. **端到端验证**：8→12→20 人逐步加压，确认每端都能看到/听到其他人、录音纪要正常、家庭隔离不被绕过。
5. **全量切换**：稳定后把 `family.livekit.enabled=true` 全开；观察一个周期后再考虑移除 mesh 代码。
6. **回滚**：把 `sfuEnabled` 关掉即可回到 mesh（上限 8 人），无需改数据。

## 6. 网络与部署要点

- **镜像版本**：compose 中起步用 `livekit/livekit-server:latest` 便于拉到可用版本；**生产上线前必须锁定到验证过的具体 tag 并记录 digest**，避免 latest 漂移引入不兼容变更。
- **媒体端口**：`50100-50200/udp` 必须整段对入站开放（少开一段就有一批人连不上）；`7881/tcp` 作为 ICE/TCP 回退，必须直接暴露，不能放在 LB/TLS 后。
- **TLS/WSS**：浏览器要求信令与媒体走安全上下文。用 Nginx/Caddy 在 `livekit.example.com:443` 终止 TLS，`location /` 反代到 `127.0.0.1:7880` 并带 `Upgrade/Connection` 头（配置见下）。
- **内置 TURN vs coturn**：本项目已有独立 coturn（3478）。默认 LiveKit 内置 TURN 关闭、复用 coturn；若改用 LiveKit 内置 TURN，需从 compose 移除 coturn 并把 `turn.enabled=true`，避免端口冲突。
- **多副本**：`redis.address` 指向同一个 Redis 后，多个 LiveKit 实例自动组集群；Java 侧信令本就支持 Redis 跨实例（`CallSignalBus`）。

Nginx 反代片段：
```nginx
server {
  listen 443 ssl http2;
  server_name livekit.example.com;
  ssl_certificate     /etc/letsencrypt/live/livekit.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/livekit.example.com/privkey.pem;
  location / {
    proxy_pass http://127.0.0.1:7880;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 86400s;
  }
}
```

## 7. 验收口径（对应验收方案第 4 节）

| 项 | 通过标准 |
| --- | --- |
| 功能 | 2 人 / 8 人 / 12 人 / 20 人通话，每端都有 N-1 路 live 远端画面与声音 |
| 弱网 | 拥塞控制开启后，模拟 200ms 延迟 / 3% 丢包下音画可用、自适应降码率生效 |
| 鉴权 | 非参与者凭 callId 请求 Token 返回 403；跨家庭 callId 返回 403；客户端无法自建房间 |
| 录音 | SFU 模式下录音→转写→纪要闭环成功，多成员发言都在纪要中 |
| 资源 | 20 人通话时 LiveKit CPU/带宽在预算内；Prometheus `:6789/metrics` 有房间/轨道指标 |
| 回滚 | 关闭 `sfuEnabled` 后 mesh 通话恢复正常 |

压测脚本：`scripts/call-browser-group.mjs` 扩展支持 `--participants=N` 与 `--sfu`，用 N 个无头浏览器上下文加入同一房间，逐 tile 断言 `<video>` 的 `readyState==="live"` 与 `videoWidth>0`（沿用现有断言口径，避免"只看到本地预览"的假阳性）。

## 8. 与现有文档的一致性修正

- `docs/实时通话设计.md` 第 1、30–32 行写"P2P mesh、8 人以上需接 SFU、本设计不覆盖"——改造后需更新为"SFU 模式 20+ 人、mesh 作为回滚保留"；
- `docs/微服务实施规划.md` 第 13 行"独立 SFU、TURN 对接和媒体房间"由"规划"变为"已实现"；
- `docs/正式环境验收执行方案.md` 第 4 节决策点标记为已定案 LiveKit。

## 9. 工作量拆解（供排期）

| 阶段 | 任务 | 产出 |
| --- | --- | --- |
| P0 基建 | 部署 LiveKit + Nginx WSS + Prometheus 抓取 | 可连接的 LiveKit 服务 |
| P0 服务端 | LiveKitProperties + Token 签发接口 + 家庭隔离校验 | `/livekit-token` 可用、鉴权测试通过 |
| P1 前端 | livekit-media.js + CallPanel 双栈改造 + feature flag | H5 两端可切 SFU 通话 |
| P1 录音 | onStream 适配 + 纪要闭环验证 | 纪要不再漏话 |
| P2 验收 | 20 人压测、弱网、鉴权、回滚演练 | 验收报告 |
| P2 文档 | 三份文档一致性更新 | 文档与实现对齐 |

预计关键路径在 P0 服务端鉴权与 P1 前端双栈，二者可并行。
