<template>
  <div class="iot-panel">
    <!-- 加载中 -->
    <div v-if="loading" class="mf-empty">正在读取家里的设备…</div>

    <!-- 未配置中枢 -->
    <template v-else-if="!hubConfigured">
      <div class="mf-card setup-card mf-rise">
        <div class="setup-head">
          <div class="setup-icon">🏠</div>
          <div class="setup-copy">
            <div class="setup-title">自动连接家里的智能中枢</div>
            <div class="setup-desc">
              App 在后台静默扫描局域网并自动发现家庭中枢，IP 与令牌由底层自动获取，无需手动填写。
              连上以后，家里每个人都能控制灯、空调、电视和音响。
            </div>
          </div>
        </div>

        <!-- 只显示扫描状态，绝不出现地址 / 令牌输入框 -->
        <div class="hub-discover" :class="hubScanState">
          <span class="hub-icon">{{ hubScanIcon }}</span>
          <div class="hub-copy">
            <b>{{ hubScanTitle }}</b>
            <span>{{ hubScanMessage }}</span>
          </div>
        </div>
        <button v-if="hubScanState === 'failed'" class="ghost-btn rescan-btn" :disabled="hubScanning" @click="autoDiscoverHub">重新扫描</button>

        <p v-if="error" class="error-line">{{ error }}</p>

        <div class="demo-block">
          <span class="demo-hint">暂时没有真实中枢？可先用演示设备体验界面效果；手动添加的设备为本地模拟，不能控制真实硬件。</span>
          <div class="demo-actions">
            <button class="ghost-btn demo-btn" :disabled="binding" @click="bindDemo">
              {{ binding ? "准备中…" : "用演示设备体验" }}
            </button>
            <button class="solid-btn demo-btn" @click="openAddDialog">添加设备</button>
          </div>
        </div>
      </div>
    </template>

    <!-- 已配置中枢 -->
    <template v-else>
      <div class="panel-bar">
        <span class="mf-chip">{{ hubIsDemo ? "演示设备" : "已连接中枢" }}</span>
        <div class="panel-bar-actions">
          <button class="mini-btn solid-mini" @click="openAddDialog">＋ 添加设备</button>
          <button class="mini-btn" @click="showUnbindConfirm = true">更换中枢</button>
        </div>
      </div>

      <div v-if="hubOffline" class="offline-banner">⚠️ 中枢暂时连不上，设备状态可能不是最新的（手动设备不受影响）</div>
      <p v-if="error" class="error-line">{{ error }}</p>

      <div v-if="loadError" class="mf-empty">{{ loadError }}</div>

      <template v-else>
        <div v-for="(devices, area) in devicesByArea" :key="area" class="area-block">
          <div class="mf-section">
            <span class="mf-section__title">{{ area }}</span>
            <span class="area-count">{{ devices.length }} 台</span>
          </div>
          <div class="devices-grid">
            <!-- 设备卡片：图标 / 名称 / 品牌 / 状态，点击进明细 -->
            <div
              v-for="device in devices"
              :key="device.entityId"
              class="device-card mf-card"
              :class="{ offline: device.isOnline === false, active: isOn(device) }"
              @click="openDetail(device)"
            >
              <div class="card-icon-wrap">
                <span class="card-icon">{{ getDeviceIcon(device) }}</span>
              </div>
              <div class="card-name">{{ device.deviceName || device.entityId }}</div>
              <div class="card-brand">{{ device.brand || brandFallback(device) }}</div>
              <div class="card-status">
                <span class="status-dot" :class="{ on: isOn(device), off: !isOn(device) }"></span>
                {{ statusText(device) }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="!areaKeys.length" class="mf-empty">
          <span class="mf-empty__mark">🛋️</span>
          <span>还没有设备，点「添加设备」自动发现中枢</span>
        </div>

        <div v-if="scenes.length" class="area-block">
          <div class="mf-section"><span class="mf-section__title">场景</span></div>
          <div class="scenes-grid">
            <button
              v-for="scene in scenes"
              :key="scene.entityId"
              class="scene-btn"
              :disabled="hubOffline || pending[scene.entityId]"
              @click="activateScene(scene)"
            >
              {{ scene.deviceName || scene.entityId }}
            </button>
          </div>
        </div>
      </template>

      <!-- 解绑确认 -->
      <div v-if="showUnbindConfirm" class="modal-overlay" @click.self="showUnbindConfirm = false">
        <div class="mf-card modal-card">
          <p class="modal-title">解绑当前中枢？</p>
          <p class="modal-desc">中枢同步的设备会清空；你手动添加的设备会保留。</p>
          <div class="modal-actions">
            <button class="ghost-btn" @click="showUnbindConfirm = false">取消</button>
            <button class="danger-btn" @click="unbindHub">确认解绑</button>
          </div>
        </div>
      </div>
    </template>

    <!-- ============ 设备明细（点击卡片进入） ============ -->
    <div v-if="detailDevice" class="detail-overlay" @click.self="closeDetail">
      <div class="detail-sheet">
        <div class="detail-head">
          <button class="back-btn" @click="closeDetail">‹ 返回</button>
          <span class="detail-title">设备明细</span>
          <span class="detail-entity">{{ detailDevice.entityId }}</span>
        </div>

        <div class="detail-hero">
          <span class="detail-icon">{{ getDeviceIcon(detailDevice) }}</span>
          <div class="detail-hero-meta">
            <div class="detail-name">{{ detailDevice.deviceName || detailDevice.entityId }}</div>
            <div class="detail-brand">{{ detailDevice.brand || brandFallback(detailDevice) }} · {{ detailDevice.areaName || '未分组' }}</div>
            <div class="detail-state">
              <span class="status-dot" :class="{ on: isOn(detailDevice), off: !isOn(detailDevice) }"></span>
              {{ statusText(detailDevice) }}
            </div>
          </div>
          <button
            v-if="isSwitchable(detailDevice)"
            class="power-btn"
            :class="{ on: isOn(detailDevice) }"
            :disabled="pending[detailDevice.entityId]"
            @click="toggle(detailDevice)"
          >{{ isOn(detailDevice) ? "⏻ 关闭" : "⏻ 开启" }}</button>
        </div>

        <div v-if="pending[detailDevice.entityId]" class="detail-pending">指令下发中…</div>

        <!-- 灯：亮度 -->
        <div v-if="detailDevice.entityType === 'light' && isOn(detailDevice) && supportsColorControl(detailDevice)" class="detail-section">
          <div class="section-label">亮度</div>
          <div class="brightness-row">
            <div
              class="track"
              @click="jumpBrightness(detailDevice, $event)"
              @touchstart.prevent="startDrag(detailDevice, $event)"
              @touchmove.prevent="moveDrag(detailDevice, $event)"
              @touchend="endDrag(detailDevice)"
              @mousedown="startDrag(detailDevice, $event)"
              @mousemove="moveDrag(detailDevice, $event)"
              @mouseup="endDrag(detailDevice)"
            >
              <div class="track-fill" :style="{ width: brightnessPct(detailDevice) + '%' }"></div>
              <div class="track-knob" :style="{ left: brightnessPct(detailDevice) + '%' }"></div>
            </div>
            <span class="range-num">{{ brightnessPct(detailDevice) }}%</span>
          </div>
        </div>

        <!-- 传感器：读数 -->
        <div v-else-if="detailDevice.entityType === 'sensor'" class="detail-section">
          <div class="section-label">当前读数</div>
          <div class="sensor-value">{{ getSensorValue(detailDevice) }}</div>
        </div>

        <!-- 空调：温度 / 模式 / 风速 -->
        <div v-else-if="detailDevice.entityType === 'climate'" class="detail-section">
          <div class="section-label">温度控制</div>
          <div class="temp-readout">
            <span class="temp-now">{{ targetTemp(detailDevice) }}<small>°C</small></span>
            <span v-if="currentTemp(detailDevice)" class="temp-current">当前室温 {{ currentTemp(detailDevice) }}°C</span>
          </div>
          <div class="temp-adjust">
            <button :disabled="pending[detailDevice.entityId]" @click="adjustTemp(detailDevice, -1)">−</button>
            <span>目标温度（16–35°C）</span>
            <button :disabled="pending[detailDevice.entityId]" @click="adjustTemp(detailDevice, 1)">＋</button>
          </div>
          <div class="chip-block">
            <div class="section-label">运行模式</div>
            <div class="chip-row">
              <button
                v-for="m in hvacModes(detailDevice)"
                :key="m"
                class="chip"
                :class="{ selected: hvacMode(detailDevice) === m }"
                :disabled="pending[detailDevice.entityId]"
                @click="setHvacMode(detailDevice, m)"
              >{{ modeLabel(m) }}</button>
            </div>
          </div>
          <div class="chip-block">
            <div class="section-label">风速</div>
            <div class="chip-row">
              <button
                v-for="f in fanModes(detailDevice)"
                :key="f"
                class="chip"
                :class="{ selected: fanMode(detailDevice) === f }"
                :disabled="pending[detailDevice.entityId]"
                @click="setFanMode(detailDevice, f)"
              >{{ modeLabel(f) }}</button>
            </div>
          </div>
        </div>

        <!-- 电视 / 音响 -->
        <div v-else-if="detailDevice.entityType === 'media_player'" class="detail-section">
          <div v-if="sources(detailDevice).length" class="chip-block">
            <div class="section-label">{{ isTv(detailDevice) ? '频道 / 信号源' : '音源' }}</div>
            <div class="chip-row">
              <button
                v-for="s in sources(detailDevice)"
                :key="s"
                class="chip"
                :class="{ selected: currentSource(detailDevice) === s }"
                :disabled="pending[detailDevice.entityId]"
                @click="selectSource(detailDevice, s)"
              >{{ s }}</button>
            </div>
          </div>
          <div class="chip-block">
            <div class="section-label">音量 <span class="label-inline">{{ volumePct(detailDevice) }}%</span></div>
            <div class="volume-row">
              <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="toggleMute(detailDevice)">
                {{ isMuted(detailDevice) ? "🔇" : "🔊" }}
              </button>
              <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="adjustVolume(detailDevice, -10)">−</button>
              <span class="volume-num">{{ volumePct(detailDevice) }}%</span>
              <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="adjustVolume(detailDevice, 10)">＋</button>
            </div>
          </div>
          <div class="chip-block">
            <div class="section-label">播放控制</div>
            <div class="play-row">
              <button :disabled="pending[detailDevice.entityId]" @click="mediaCmd(detailDevice, 'media_previous_track')">⏮</button>
              <button
                class="play-main"
                :disabled="pending[detailDevice.entityId]"
                @click="mediaCmd(detailDevice, detailDevice.state === 'paused' ? 'media_play' : 'media_pause')"
              >{{ detailDevice.state === 'paused' ? '▶' : '⏸' }}</button>
              <button :disabled="pending[detailDevice.entityId]" @click="mediaCmd(detailDevice, 'media_next_track')">⏭</button>
            </div>
          </div>
        </div>

        <!-- 绑定后：连接诊断（日常控制走网络，不再依赖红外） -->
        <div class="detail-section test-section">
          <div class="section-label">连接诊断</div>
          <button
            v-if="!hubIsDemo"
            class="ghost-btn test-btn"
            :disabled="testingDevice === detailDevice.entityId"
            @click="testDevice(detailDevice)"
          >{{ testingDevice === detailDevice.entityId ? "诊断中…" : "检测连接状态" }}</button>
          <div v-if="testResult" class="test-result" :class="{ fail: !testResult.ok }">
            <span class="test-badge" :class="{ real: testResult.realHardware, sim: !testResult.realHardware }">
              {{ testResult.realHardware ? (testResult.ok ? "响应正常" : "无响应") : "本地模拟" }}
            </span>
            <span class="test-msg">{{ testResult.message }}</span>
          </div>
          <p class="test-hint">设备已绑定并纳入家庭设备体系，日常开关与调控<b>直接走网络、不再依赖红外</b>；上方明细里可直接调温度 / 频道 / 音量。此按钮仅用于诊断中枢连接。</p>
        </div>

        <div class="detail-footnote">点空白处或「返回」关闭明细</div>
      </div>
    </div>

    <!-- 添加 / 编辑设备弹窗 -->
    <div v-if="showDeviceDialog" class="modal-overlay" @click.self="closeDeviceDialog">
      <div class="mf-card modal-card device-dialog add-modal">
        <p class="modal-title">添加设备</p>

        <!-- 后台自动发现中枢 → 设备配网 → 用户确认红外验证 → 纳入家庭 -->
          <p class="modal-desc">添加设备<b>无需填写地址或令牌</b>：进入页面后 App 在后台静默扫描局域网、自动发现家庭中枢并建立连接。设备必须由你确认红外响应后才能加入家庭。</p>
          <div class="add-steps">
            <span class="add-step" :class="{ done: addStage >= 1 }">① 自动发现中枢</span><span class="add-step-line"></span>
            <span class="add-step" :class="{ done: addStage >= 2 }">② 设备配网</span><span class="add-step-line"></span>
            <span class="add-step" :class="{ done: addStage >= 3 }">③ 红外验证</span><span class="add-step-line"></span>
            <span class="add-step" :class="{ done: addStage >= 4 }">④ 纳入家庭</span>
          </div>

          <!-- 中枢发现状态：只显示状态文字，绝无输入框 -->
          <div class="hub-discover" :class="hubScanState">
            <span class="hub-icon">{{ hubScanIcon }}</span>
            <div class="hub-copy">
              <b>{{ hubScanTitle }}</b>
              <span>{{ hubScanMessage }}</span>
            </div>
          </div>
          <button v-if="hubScanState === 'failed'" class="ghost-btn rescan-btn" :disabled="hubScanning" @click="autoDiscoverHub">重新扫描</button>

          <template v-if="hubScanState === 'found'">
            <p class="pair-tip">请让待添加的设备处于<b>配网状态</b>（长按配网键、指示灯闪烁）。App 会自动发现待绑定设备。</p>
            <button class="ghost-btn rescan-btn" @click="refreshDiscoveredDevices">重新发现待绑定设备</button>

            <div v-if="discoveredDevices.length" class="disc-list">
              <div
                v-for="d in discoveredDevices"
                :key="d.entityId"
                class="disc-row"
                :class="{ selected: irCurrentId === d.entityId }"
                @click="selectIrDevice(d.entityId)"
              >
                <div class="disc-info">
                  <b>{{ d.deviceName || d.entityId }}</b>
                  <span>{{ typeLabel(d.entityType) }} · {{ d.brand || '—' }} · {{ d.areaName || '未分组' }}</span>
                </div>
                <span v-if="irResult(d) === 'ok'" class="ir-badge ok">✓ 验证通过</span>
                <span v-else-if="irResult(d) === 'fail'" class="ir-badge fail">✗ 未响应</span>
                <span v-else class="ir-badge pending">待验证</span>
              </div>
            </div>

            <div class="ir-verify">
              <div class="ir-verify-label">红外验证（强制阻断）</div>
              <p class="ir-verify-tip">当前验证：<b>{{ irCurrentName }}</b>。点「红外测试」发射指令后，需由你确认设备是否闪烁 / 蜂鸣。</p>
              <button class="ghost-btn ir-test-btn" :disabled="irTesting || !irCurrentId" @click="emitIrTest">
                {{ irTesting ? "已发射，等待你确认…" : "红外测试" }}
              </button>

              <div v-if="irAwaitingConfirm" class="ir-confirm">
                <p>请观察设备：<b>刚才是否闪烁 / 蜂鸣？</b><br />红外为单向发射，系统无法自动检测，需由你确认。</p>
                <div class="ir-confirm-actions">
                  <button class="ghost-btn" @click="confirmIrResponse(false)">没反应，重试</button>
                  <button class="solid-btn" @click="confirmIrResponse(true)">有响应，验证通过</button>
                </div>
              </div>
              <p v-if="irResultText" class="ir-verify-result" :class="irResultClass">{{ irResultText }}</p>

              <button class="solid-btn full-btn" :disabled="irVerifiedCount === 0" @click="finishIrAdd">
                下一步 · 加入家庭（{{ irVerifiedCount }}）
              </button>
            </div>
            <p class="bind-note">加入家庭后，家庭成员在 App 内<b>直接通过网络下发指令</b>开关与调控，<b>不再使用红外</b>；设备明细里可调温度 / 频道 / 音量。</p>
          </template>
        <div class="modal-actions"><button class="ghost-btn" @click="closeDeviceDialog">取消</button></div>
      </div>
    </div>
  </div>
</template>

<script>
import { request } from "@app/utils/request";
import {
  getLightBrightness, getSensorValue, supportsColorControl,
  isDeviceOn, getTargetTemp, getCurrentTemp, getHvacModes, getFanModes,
  getHvacMode, getFanMode, getVolumePct, isMuted, getSources,
  getCurrentSource, getMediaTitle, isTv, modeLabel,
} from "./iot-logic.mjs";

const DEMO_HUB = { baseUrl: "demo://local", accessToken: "demo" };

const BRAND_FALLBACK = {
  light: "智能照明", switch: "智能插座", sensor: "传感器",
  climate: "智能空调", media_player: "智能影音", scene: "场景",
};

export default {
  name: "IotPanel",

  data() {
    return {
      loading: true,
      hubConfigured: false,
      hubIsDemo: false,
      binding: false,
      hubScanState: "scanning",
      hubScanning: false,
      error: "",
      loadError: "",
      devicesByArea: {},
      scenes: [],
      pending: {},
      dragPct: {},
      dragging: null,
      showUnbindConfirm: false,
      pollTimer: null,
      detailDevice: null,
      showDeviceDialog: false,
      testResult: null,
      testingDevice: null,
      addStage: 0,
      discoveredDevices: [],
      irResults: {},
      irCurrentId: null,
      irTesting: false,
      irAwaitingConfirm: false,
      irResultText: "",
      irResultClass: "",
    };
  },

  computed: {
    areaKeys() {
      return Object.keys(this.devicesByArea);
    },
    hubOffline() {
      const all = this.areaKeys.flatMap(area => this.devicesByArea[area] || []);
      const hubDevices = all.filter(d => d.deviceSource !== "MANUAL");
      return hubDevices.length > 0 && hubDevices.every(d => d.isOnline === false);
    },
    hubScanIcon() {
      if (this.hubScanState === "found") return "✓";
      if (this.hubScanState === "failed") return "✗";
      return "◎";
    },
    hubScanTitle() {
      if (this.hubScanState === "found") return "已自动发现家庭中枢";
      if (this.hubScanState === "failed") return "未发现家庭中枢";
      return "正在扫描局域网…";
    },
    hubScanMessage() {
      if (this.hubScanState === "found") return "已静默建立连接，无需填写地址或令牌";
      if (this.hubScanState === "failed") return "请检查中枢是否通电/连接同一WiFi";
      return "正在自动发现家庭中枢，请稍候";
    },
    irCurrentName() {
      const d = this.discoveredDevices.find(x => x.entityId === this.irCurrentId);
      return d ? (d.deviceName || d.entityId) : "—";
    },
    irVerifiedCount() {
      return this.discoveredDevices.filter(d => this.irResults[d.entityId] === "ok").length;
    },
  },

  methods: {
    getLightBrightness, getSensorValue, supportsColorControl,
    isOn: isDeviceOn, targetTemp: getTargetTemp, currentTemp: getCurrentTemp,
    hvacModes: getHvacModes, fanModes: getFanModes, hvacMode: getHvacMode,
    fanMode: getFanMode, volumePct: getVolumePct, isMuted, sources: getSources,
    currentSource: getCurrentSource, mediaTitle: getMediaTitle, isTv, modeLabel,

    getDeviceIcon(device) {
      const icons = {
        light: "💡", switch: "🔌", sensor: "📊", scene: "🎬",
        climate: "❄️", media_player: this.isTv(device) ? "📺" : "🔊",
      };
      return icons[device.entityType] || "📱";
    },

    isSwitchable(device) {
      // 空调、灯、插座、电视音响都提供电源开关；传感器只读
      return ["light", "switch", "climate", "media_player"].includes(device.entityType);
    },

    brandFallback(device) {
      return BRAND_FALLBACK[device.entityType] || "智能设备";
    },

    statusText(device) {
      if (device.isOnline === false) return "离线";
      if (device.entityType === "sensor") return "监测中";
      if (device.entityType === "climate") return this.modeLabel(this.hvacMode(device)) + " " + this.targetTemp(device) + "°C";
      if (device.entityType === "media_player") {
        if (device.state === "paused") return "已暂停";
        return device.state === "on" ? "播放中" : "已关闭";
      }
      return device.state === "on" ? "已开启" : "已关闭";
    },

    // -------- 明细 --------
    openDetail(device) {
      this.detailDevice = device;
      this.testResult = null;
    },
    closeDetail() {
      this.detailDevice = null;
    },

    /** 供父页面在切换标签时主动刷新 */
    load() {
      return this.init();
    },

    async init() {
      this.loading = true;
      this.loadError = "";
      try {
        const hub = await request("/iot/hub");
        this.hubConfigured = !!hub?.configured;
        this.hubIsDemo = !!hub?.demo;
        await this.loadDevices();
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "读取中枢配置失败";
      } finally {
        this.loading = false;
      }
    },

    /** 后台静默发现中枢：地址/令牌由服务端底层封装，前端只见状态文案。 */
    async autoDiscoverHub() {
      if (this.hubScanning) return;
      this.hubScanning = true;
      this.hubScanState = "scanning";
      this.error = "";
      try {
        const res = await request("/iot/hub/auto-discover", { method: "POST" });
        if (res && res.found) {
          this.hubScanState = "found";
          this.hubConfigured = true;
          this.hubIsDemo = !!res.demo;
          await this.loadDevices();
          this.refreshDiscoveredDevices();
          this.addStage = Math.max(this.addStage, 1);
        } else {
          this.hubScanState = "failed";
        }
      } catch (e) {
        this.hubScanState = "failed";
      } finally {
        this.hubScanning = false;
      }
    },

    async bindDemo() {
      this.error = "";
      await this.submitHub(DEMO_HUB);
    },

    async submitHub(payload) {
      this.binding = true;
      try {
        await request("/iot/hub", { method: "PUT", data: payload });
        this.hubConfigured = true;
        this.hubIsDemo = payload.baseUrl === DEMO_HUB.baseUrl;
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "绑定失败";
      } finally {
        this.binding = false;
      }
    },

    async unbindHub() {
      this.showUnbindConfirm = false;
      try {
        await request("/iot/hub", { method: "DELETE" });
        this.hubConfigured = false;
        this.hubIsDemo = false;
        this.detailDevice = null;
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "解绑失败";
      }
    },

    async loadDevices() {
      this.loadError = "";
      try {
        const [areas, scenes] = await Promise.all([
          request("/iot/devices"),
          request("/iot/scenes"),
        ]);
        const grouped = {};
        for (const group of areas || []) {
          grouped[group.areaName || "未分组"] = group.devices || [];
        }
        this.devicesByArea = grouped;
        this.scenes = scenes || [];
        // 明细里也保持最新状态
        if (this.detailDevice) {
          const all = Object.values(this.devicesByArea).flat();
          const fresh = all.find(d => d.entityId === this.detailDevice.entityId);
          this.detailDevice = fresh || null;
        }
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "设备加载失败";
      }
    },

    // -------- 通用命令 --------
    async toggle(device) {
      await this.executeCommand(device, this.isOn(device) ? "turn_off" : "turn_on");
    },

    brightnessPct(device) {
      const dragging = this.dragPct[device.entityId];
      return dragging === undefined ? getLightBrightness(device) : dragging;
    },

    ratioOf(event) {
      const el = event.currentTarget;
      const rect = el.getBoundingClientRect();
      if (!rect.width) return 0;
      const point = event.touches && event.touches[0] ? event.touches[0] : event;
      const ratio = ((point.clientX - rect.left) / rect.width) * 100;
      return Math.min(100, Math.max(0, Math.round(ratio)));
    },

    startDrag(device, event) {
      this.dragging = device.entityId;
      this.dragPct = { ...this.dragPct, [device.entityId]: this.ratioOf(event) };
    },

    moveDrag(device, event) {
      if (this.dragging !== device.entityId) return;
      this.dragPct = { ...this.dragPct, [device.entityId]: this.ratioOf(event) };
    },

    endDrag(device) {
      if (this.dragging !== device.entityId) return;
      const pct = this.dragPct[device.entityId] ?? getLightBrightness(device);
      this.dragging = null;
      this.commitBrightness(device, pct);
    },

    jumpBrightness(device, event) {
      if (this.dragging) return;
      this.commitBrightness(device, this.ratioOf(event));
    },

    commitBrightness(device, pct) {
      const next = { ...this.dragPct };
      delete next[device.entityId];
      this.dragPct = next;
      this.executeCommand(device, "turn_on", { brightness: Math.round((pct / 100) * 255) });
    },

    // -------- 空调 --------
    async adjustTemp(device, delta) {
      const next = Math.min(35, Math.max(16, this.targetTemp(device) + delta));
      await this.executeCommand(device, "set_temperature", { temperature: next });
    },
    async setHvacMode(device, mode) {
      await this.executeCommand(device, "set_hvac_mode", { hvac_mode: mode });
    },
    async setFanMode(device, mode) {
      await this.executeCommand(device, "set_fan_mode", { fan_mode: mode });
    },

    // -------- 电视 / 音响 --------
    async selectSource(device, source) {
      await this.executeCommand(device, "select_source", { source });
    },
    async adjustVolume(device, delta) {
      const next = Math.min(100, Math.max(0, this.volumePct(device) + delta));
      await this.executeCommand(device, "volume_set", { volume_level: Number((next / 100).toFixed(2)) });
    },
    async toggleMute(device) {
      await this.executeCommand(device, "volume_mute", { is_volume_muted: !this.isMuted(device) });
    },
    async mediaCmd(device, command) {
      await this.executeCommand(device, command);
    },

    // -------- 添加设备：自动发现中枢 + 用户确认红外验证 --------
    typeLabel(t) {
      const m = { climate: "空调", media_player: "电视 / 音响", light: "灯", switch: "插座 / 开关", sensor: "传感器" };
      return m[t] || t;
    },

    /** 从已同步的设备里列出待绑定设备（中枢发现成功后自动执行）。 */
    refreshDiscoveredDevices() {
      const all = Object.values(this.devicesByArea).flat();
      this.discoveredDevices = all.filter(d => d.deviceSource !== "MANUAL");
      this.irResults = {};
      this.irCurrentId = this.discoveredDevices.length ? this.discoveredDevices[0].entityId : null;
      this.irTesting = false;
      this.irAwaitingConfirm = false;
      this.irResultText = "";
      this.irResultClass = "";
      if (this.discoveredDevices.length) this.addStage = Math.max(this.addStage, 2);
    },

    irResult(device) {
      return this.irResults[device.entityId] || null;
    },

    selectIrDevice(id) {
      this.irCurrentId = id;
      this.irAwaitingConfirm = false;
      this.irTesting = false;
      this.irResultText = "";
      this.irResultClass = "";
    },

    /** 只负责发射红外测试指令，不做任何自动判定。 */
    emitIrTest() {
      if (!this.irCurrentId) return;
      this.irTesting = true;
      this.irResultText = "已发射红外测试指令，请观察设备是否闪烁 / 蜂鸣…";
      this.irResultClass = "pending";
      this.irAwaitingConfirm = true;   // 判定权交给用户
    },

    /** 用户确认设备是否产生物理响应：确认「有响应」才放行。 */
    confirmIrResponse(responded) {
      const id = this.irCurrentId;
      this.irAwaitingConfirm = false;
      this.irTesting = false;
      if (responded) {
        this.irResults = { ...this.irResults, [id]: "ok" };
        this.irResultText = "✓ 验证通过（由你确认设备有响应），「下一步」已激活";
        this.irResultClass = "ok";
        this.addStage = 3;
      } else {
        this.irResults = { ...this.irResults, [id]: "fail" };
        this.irResultText = "未检测到响应，请重试（「下一步」保持不可用，停留在本环节）";
        this.irResultClass = "fail";
        this.addStage = 2;   // 无限停留在验证环节
      }
    },

    async finishIrAdd() {
      if (this.irVerifiedCount === 0) return;
      this.addStage = 4;
      this.closeDeviceDialog();
      await this.loadDevices();
    },

    // -------- 添加设备弹窗 --------
    openAddDialog() {
      this.addStage = 0;
      this.discoveredDevices = [];
      this.irResults = {};
      this.irCurrentId = null;
      this.irTesting = false;
      this.irAwaitingConfirm = false;
      this.irResultText = "";
      this.irResultClass = "";
      this.showDeviceDialog = true;
      this.autoDiscoverHub();   // 进入添加设备页面即后台静默扫描
    },

    closeDeviceDialog() {
      this.showDeviceDialog = false;
    },

    // -------- 设备响应探测 --------
    async testDevice(device) {
      this.testingDevice = device.entityId;
      this.testResult = null;
      try {
        const res = await request(`/iot/devices/${device.entityId}/test`, { method: "POST" });
        this.testResult = res;
      } catch (e) {
        this.testResult = { ok: false, realHardware: true, message: e.message || "探测失败" };
      } finally {
        this.testingDevice = null;
      }
    },

    // -------- 命令下发 --------
    async executeCommand(device, commandType, parameters = null) {
      this.pending = { ...this.pending, [device.entityId]: true };
      this.error = "";
      try {
        await request(`/iot/devices/${device.entityId}/command`, {
          method: "POST", data: { commandType, parameters },
        });
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "操作失败，请稍后再试";
      } finally {
        const next = { ...this.pending };
        delete next[device.entityId];
        this.pending = next;
      }
    },

    async activateScene(scene) {
      this.pending = { ...this.pending, [scene.entityId]: true };
      this.error = "";
      try {
        await request(`/iot/scenes/${scene.entityId}/activate`, { method: "POST" });
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "场景触发失败";
      } finally {
        const next = { ...this.pending };
        delete next[scene.entityId];
        this.pending = next;
      }
    },
  },

  mounted() {
    this.init();
    // 启动即后台静默扫描局域网中枢：UI 只见状态文案，不出现任何输入框
    this.autoDiscoverHub();
    this.pollTimer = setInterval(() => {
      if (!this.loading) this.loadDevices();
    }, 5000);
  },

  beforeUnmount() {
    if (this.pollTimer) clearInterval(this.pollTimer);
  },
};
</script>

<style scoped>
.iot-panel { padding: 4px 0 24px; }

/* ---------- 未配置：连接中枢 ---------- */
.setup-card { padding: 22px 20px; }
.setup-head { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 20px; }
.setup-icon {
  display: grid; place-items: center; width: 52px; height: 52px; flex: none;
  border-radius: 26px; font-size: 24px;
  background: rgba(255, 255, 255, 0.72); box-shadow: var(--sh-1);
}
.setup-title { font-size: 17px; font-weight: 650; color: var(--c-ink); }
.setup-desc { margin-top: 6px; font-size: 12px; line-height: 1.7; color: var(--c-muted); }
.field { display: block; margin-bottom: 14px; }
.field-label { display: block; margin-bottom: 7px; font-size: 12px; font-weight: 600; color: var(--c-ink-2); }
.field-input {
  width: 100%; height: 46px; padding: 0 16px; box-sizing: border-box;
  font-size: 14px; color: var(--c-ink);
  background: rgba(255, 255, 255, 0.66);
  border: 1px solid var(--c-glass-line); border-radius: 16px; outline: none;
}
.field-input:focus { border-color: rgba(79, 125, 198, 0.42); box-shadow: 0 0 0 3px rgba(134, 187, 255, 0.22); }
.field-hint { display: block; margin-top: 6px; font-size: 11px; color: var(--c-muted); }
.setup-actions { display: flex; gap: 10px; margin-top: 18px; }
.setup-actions button, .modal-actions button {
  border: none; border-radius: 30px; font-size: 14px; font-weight: 600; height: 46px; letter-spacing: 0.02em;
}
.ghost-btn { color: var(--c-ink-2); background: rgba(255, 255, 255, 0.62); border: 1px solid var(--c-line); }
.solid-btn { color: #f4f7ff; background: #4f7dc6; box-shadow: 0 8px 20px rgba(134, 187, 255, 0.45); }
.demo-block { margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--c-line); }
.demo-hint { display: block; font-size: 11px; line-height: 1.6; color: var(--c-muted); margin-bottom: 10px; }
.demo-actions { display: flex; gap: 10px; }
.demo-actions button { flex: 1; padding: 0 8px; }

/* ---------- 面板栏 ---------- */
.panel-bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; gap: 10px; }
.panel-bar-actions { display: flex; gap: 8px; }
.mf-chip {
  display: inline-block; padding: 5px 12px; border-radius: 20px;
  font-size: 11px; font-weight: 600; color: #4f7dc6; background: rgba(134, 187, 255, 0.18);
}
.mini-btn {
  padding: 7px 13px; border-radius: 18px; font-size: 12px; font-weight: 600;
  color: var(--c-ink-2); background: rgba(255, 255, 255, 0.62); border: 1px solid var(--c-line);
}
.solid-mini { color: #fff; background: #4f7dc6; border-color: #4f7dc6; }
.offline-banner {
  padding: 10px 14px; margin-bottom: 12px; border-radius: 12px;
  font-size: 12px; color: #a8631a; background: rgba(255, 190, 100, 0.18);
}
.error-line { margin: 8px 0; font-size: 12px; color: #c0392b; }

/* ---------- 区域 / 设备卡片 ---------- */
.area-block { margin-bottom: 20px; }
.mf-section { display: flex; align-items: baseline; gap: 8px; margin: 4px 0 10px; }
.mf-section__title { font-size: 14px; font-weight: 650; color: var(--c-ink); }
.area-count { font-size: 11px; color: var(--c-muted); }
.devices-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
.device-card {
  padding: 16px 12px; text-align: center; cursor: pointer; position: relative;
  transition: transform 0.15s, opacity 0.2s;
}
.device-card:active { transform: scale(0.97); }
.device-card.offline { opacity: 0.55; }
.device-card.active { box-shadow: 0 6px 22px rgba(134, 187, 255, 0.25); }
.card-icon-wrap {
  display: grid; place-items: center; width: 52px; height: 52px; margin: 0 auto 10px;
  border-radius: 26px; background: rgba(255, 255, 255, 0.72); box-shadow: var(--sh-1);
}
.card-icon { font-size: 26px; }
.card-name {
  font-size: 13px; font-weight: 600; color: var(--c-ink);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.card-brand { font-size: 11px; color: var(--c-muted); margin-top: 3px; }
.card-status { display: flex; align-items: center; justify-content: center; gap: 5px; margin-top: 9px; font-size: 11px; color: var(--c-ink-2); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.status-dot.on { background: #37b26b; }
.status-dot.off { background: #b8bfc9; }
.card-manual {
  position: absolute; top: 8px; right: 8px; padding: 2px 7px; border-radius: 10px;
  font-size: 9px; color: #4f7dc6; background: rgba(134, 187, 255, 0.2);
}

/* ---------- 场景 ---------- */
.scenes-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 10px; }
.scene-btn {
  padding: 14px 8px; border-radius: 16px; font-size: 13px; font-weight: 600;
  color: var(--c-ink); background: rgba(255, 255, 255, 0.66); border: 1px solid var(--c-line);
}
.mf-empty { text-align: center; padding: 30px 10px; font-size: 13px; color: var(--c-muted); }
.mf-empty__mark { display: block; font-size: 36px; margin-bottom: 8px; }

/* ---------- 设备明细 ---------- */
.detail-overlay {
  position: fixed; inset: 0; z-index: 90;
  display: flex; align-items: flex-end; justify-content: center;
  background: rgba(20, 30, 50, 0.4);
}
.detail-sheet {
  width: 100%; max-width: 480px; max-height: 88vh; overflow-y: auto;
  padding: 16px 20px 24px;
  background: var(--c-glass, #f7f9fc);
  border-radius: 22px 22px 0 0;
  box-shadow: 0 -8px 32px rgba(0, 0, 0, 0.14);
}
.detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.back-btn {
  padding: 6px 12px; border-radius: 16px; font-size: 13px;
  color: var(--c-ink-2); background: rgba(255, 255, 255, 0.7); border: 1px solid var(--c-line);
}
.detail-title { font-size: 15px; font-weight: 650; color: var(--c-ink); }
.detail-entity { margin-left: auto; font-size: 10px; color: var(--c-muted); max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-hero { display: flex; align-items: center; gap: 14px; padding: 14px; border-radius: 18px; background: rgba(255, 255, 255, 0.66); }
.detail-icon { font-size: 40px; }
.detail-hero-meta { flex: 1; min-width: 0; }
.detail-name { font-size: 17px; font-weight: 650; color: var(--c-ink); }
.detail-brand { font-size: 11px; color: var(--c-muted); margin-top: 3px; }
.detail-state { display: flex; align-items: center; gap: 5px; font-size: 12px; color: var(--c-ink-2); margin-top: 6px; }
.detail-pending { margin-top: 8px; font-size: 11px; color: #4f7dc6; }
.detail-section { margin-top: 18px; padding: 14px; border-radius: 18px; background: rgba(255, 255, 255, 0.55); }
.section-label { font-size: 12px; font-weight: 600; color: var(--c-ink-2); margin-bottom: 10px; }
.label-inline { float: right; color: #4f7dc6; }
.chip-block { margin-top: 16px; }
.chip-block:first-child { margin-top: 0; }

/* ---------- 电源开关：按钮控件，不使用滑块 ---------- */
.power-btn { flex: none; margin-left: auto; padding: 8px 14px; border-radius: 14px; border: 1px solid var(--c-primary); background: transparent; color: var(--c-primary); font-size: 12px; font-weight: 700; white-space: nowrap; }
.power-btn.on { background: var(--c-primary); color: #fff; }
.power-btn:active { transform: scale(0.96); }
.power-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ---------- 添加设备：红外验证 ---------- */
.modal-card.add-modal { max-width: 382px; max-height: 88vh; overflow-y: auto; }
.add-tabs { display: flex; gap: 5px; margin: 10px 0 14px; padding: 4px; border-radius: 14px; background: rgba(134, 187, 255, 0.14); }
.add-tab { flex: 1; padding: 8px 5px; border: 0; border-radius: 10px; background: transparent; color: var(--c-muted); font-size: 11px; font-weight: 600; cursor: pointer; line-height: 1.4; }
.add-tab.active { background: rgba(255, 255, 255, 0.92); color: var(--c-primary); box-shadow: 0 2px 8px rgba(134, 187, 255, 0.28); }
.add-steps { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-bottom: 14px; }
.add-step { font-size: 10px; color: var(--c-muted); white-space: nowrap; }
.add-step.done { color: var(--c-primary); font-weight: 700; }
.add-step-line { flex: 1; min-width: 6px; height: 1px; background: var(--c-line); }
.hub-test-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.hub-test-row .ghost-btn { flex: 1; }
.hub-test-state { font-size: 11px; font-weight: 600; color: var(--c-muted); white-space: nowrap; }
.hub-test-state.ok { color: #2f8f5b; }
.hub-test-state.fail { color: #d05a4e; }
.ir-sim { display: flex; align-items: center; gap: 7px; margin: 0 0 10px; font-size: 11px; color: var(--c-muted); cursor: pointer; }
.bind-note { margin: 2px 0 12px; font-size: 11px; line-height: 1.7; color: var(--c-muted); padding: 9px 11px; border-radius: 12px; background: rgba(134, 187, 255, 0.12); }
.disc-list { margin: 6px 0 12px; max-height: 205px; overflow-y: auto; }
.disc-row { display: flex; align-items: center; gap: 8px; padding: 9px 10px; margin-bottom: 7px; border: 1px solid var(--c-glass-line); border-radius: 14px; background: rgba(255, 255, 255, 0.5); }
.disc-info { flex: 1; min-width: 0; }
.disc-info b { display: block; font-size: 12px; color: var(--c-ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.disc-info span { font-size: 10px; color: var(--c-muted); }
.mini-test { flex: none; padding: 5px 9px; border-radius: 12px; border: 1px solid var(--c-line); background: transparent; color: var(--c-primary); font-size: 10px; font-weight: 600; cursor: pointer; }
.ir-badge { flex: none; font-size: 10px; font-weight: 600; padding: 3px 8px; border-radius: 10px; white-space: nowrap; }
.ir-badge.ok { color: #2f8f5b; background: rgba(55, 178, 107, 0.16); }
.ir-badge.fail { color: #d05a4e; background: rgba(220, 100, 90, 0.14); }
.ir-badge.pending { color: var(--c-muted); background: rgba(120, 130, 150, 0.14); }
.ir-tip { margin: 0 0 10px; font-size: 11px; line-height: 1.6; color: #c8791f; }
.full-btn { width: 100%; height: 42px; border-radius: 22px; margin-bottom: 12px; font-size: 13px; }
.full-btn:disabled { opacity: 0.45; cursor: not-allowed; }
/* 后台静默发现中枢 */
.hub-discover { display: flex; align-items: center; gap: 10px; padding: 12px 14px; margin-bottom: 14px; border-radius: 16px; border: 1px solid var(--c-glass-line); background: rgba(134, 187, 255, 0.12); }
.hub-icon { font-size: 20px; color: var(--c-primary); flex: none; }
.hub-copy { flex: 1; min-width: 0; }
.hub-copy b { display: block; font-size: 13px; color: var(--c-ink); }
.hub-copy span { display: block; font-size: 11px; line-height: 1.5; color: var(--c-muted); margin-top: 3px; }
.hub-discover.found .hub-icon { color: #2f8f5b; }
.hub-discover.failed { background: rgba(220, 100, 90, 0.1); border-color: rgba(220, 100, 90, 0.3); }
.hub-discover.failed .hub-icon, .hub-discover.failed .hub-copy b { color: #d05a4e; }
.rescan-btn { width: 100%; height: 38px; border-radius: 19px; margin-bottom: 12px; font-size: 13px; }
.pair-tip { margin: 0 0 10px; font-size: 11px; line-height: 1.7; color: var(--c-ink-2); padding: 9px 11px; border-radius: 12px; background: rgba(255, 255, 255, 0.4); }
.pair-tip b { color: var(--c-primary); }
.disc-row { cursor: pointer; }
.disc-row.selected { border-color: var(--c-primary); background: rgba(134, 187, 255, 0.16); }
/* 强制红外验证：判定权在用户 */
.ir-verify { margin: 6px 0 12px; padding: 12px; border-radius: 16px; border: 1px solid var(--c-glass-line); background: rgba(255, 255, 255, 0.42); }
.ir-verify-label { font-size: 12px; font-weight: 700; color: var(--c-ink); margin-bottom: 6px; }
.ir-verify-tip { font-size: 11px; line-height: 1.6; color: var(--c-muted); margin: 0 0 10px; }
.ir-verify-tip b { color: var(--c-primary); }
.ir-test-btn { width: 100%; height: 42px; border-radius: 22px; font-size: 13px; margin-bottom: 10px; }
.ir-test-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.ir-confirm { margin: 0 0 10px; padding: 10px 11px; border-radius: 14px; background: rgba(134, 187, 255, 0.14); border: 1px solid var(--c-glass-line); }
.ir-confirm p { margin: 0 0 10px; font-size: 11px; line-height: 1.7; color: var(--c-ink-2); }
.ir-confirm p b { color: var(--c-primary); }
.ir-confirm-actions { display: flex; gap: 8px; }
.ir-confirm-actions button { flex: 1; height: 36px; border-radius: 18px; font-size: 12px; font-weight: 600; }
.ir-verify-result { font-size: 12px; line-height: 1.6; margin: 0 0 10px; }
.ir-verify-result.ok { color: #2f8f5b; font-weight: 600; }
.ir-verify-result.fail { color: #d05a4e; font-weight: 600; }
.ir-verify-result.pending { color: var(--c-muted); }
.disc-row input:disabled { opacity: 0.35; cursor: not-allowed; }

/* ---------- 灯亮度 ---------- */
.brightness-row { display: flex; align-items: center; gap: 10px; }
.track { flex: 1; height: 8px; border-radius: 4px; background: rgba(120, 130, 150, 0.2); position: relative; }
.track-fill { position: absolute; left: 0; top: 0; height: 100%; border-radius: 4px; background: linear-gradient(90deg, #86bbff, #4f7dc6); }
.track-knob { position: absolute; top: 50%; width: 16px; height: 16px; border-radius: 50%; background: #fff; box-shadow: 0 1px 5px rgba(0, 0, 0, 0.22); transform: translate(-50%, -50%); }
.range-num { width: 38px; text-align: right; font-size: 12px; color: var(--c-ink-2); }
.sensor-value { font-size: 22px; font-weight: 600; color: var(--c-ink); }

/* ---------- 空调 ---------- */
.temp-readout { display: flex; align-items: baseline; gap: 12px; }
.temp-now { font-size: 36px; font-weight: 650; color: var(--c-ink); line-height: 1; }
.temp-now small { font-size: 16px; margin-left: 2px; }
.temp-current { font-size: 12px; color: var(--c-muted); }
.temp-adjust { display: flex; align-items: center; gap: 14px; margin: 14px 0; }
.temp-adjust button {
  width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.8); color: var(--c-ink); font-size: 20px; line-height: 1;
}
.temp-adjust span { font-size: 12px; color: var(--c-muted); }
.chip-row { display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  padding: 7px 13px; border-radius: 16px; font-size: 12px;
  border: 1px solid var(--c-line); background: rgba(255, 255, 255, 0.7); color: var(--c-ink-2);
}
.chip.selected { background: #4f7dc6; border-color: #4f7dc6; color: #fff; }

/* ---------- 电视 / 音响 ---------- */
.volume-row { display: flex; align-items: center; gap: 10px; }
.round-btn {
  width: 34px; height: 34px; border-radius: 50%; border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.75); font-size: 14px; color: var(--c-ink);
}
.volume-num { min-width: 40px; text-align: center; font-size: 13px; color: var(--c-ink-2); }
.play-row { display: flex; align-items: center; justify-content: center; gap: 22px; }
.play-row button {
  width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.75); color: var(--c-ink); font-size: 15px;
}
.play-row .play-main { width: 50px; height: 50px; background: #4f7dc6; border-color: #4f7dc6; color: #fff; font-size: 18px; }
.detail-manual-actions { display: flex; gap: 10px; margin-top: 18px; }
.detail-manual-actions button { flex: 1; border-radius: 26px; font-size: 13px; font-weight: 600; height: 44px; }
.danger-btn { color: #fff; background: #c0392b; }
.detail-footnote { text-align: center; font-size: 11px; color: var(--c-muted); margin-top: 18px; }

/* ---------- 设备响应探测 ---------- */
.test-section { margin-top: 16px; }
.test-btn { width: 100%; height: 42px; border-radius: 26px; font-size: 13px; font-weight: 600; }
.test-result {
  display: flex; align-items: flex-start; gap: 8px; margin-top: 12px; padding: 10px 12px;
  border-radius: 14px; background: rgba(134, 187, 255, 0.14);
}
.test-result.fail { background: rgba(220, 100, 90, 0.12); }
.test-badge { flex: none; padding: 2px 8px; border-radius: 10px; font-size: 10px; font-weight: 600; }
.test-badge.real { color: #2f8f5b; background: rgba(55, 178, 107, 0.16); }
.test-badge.sim { color: #4f7dc6; background: rgba(134, 187, 255, 0.22); }
.test-msg { font-size: 12px; line-height: 1.6; color: var(--c-ink-2); }
.test-hint { margin-top: 10px; font-size: 11px; line-height: 1.6; color: var(--c-muted); }

/* ---------- 弹窗 ---------- */
.modal-overlay {
  position: fixed; inset: 0; z-index: 100;
  display: grid; place-items: center; padding: 20px;
  background: rgba(20, 30, 50, 0.36);
}
.modal-card { width: 100%; max-width: 360px; padding: 22px 20px; }
.modal-title { margin: 0 0 6px; font-size: 17px; font-weight: 650; color: var(--c-ink); }
.modal-desc { margin: 0 0 16px; font-size: 12px; line-height: 1.6; color: var(--c-muted); }
.modal-actions { display: flex; gap: 10px; margin-top: 18px; }
.modal-actions button { flex: 1; }
.device-dialog select.field-input { appearance: auto; }
</style>
