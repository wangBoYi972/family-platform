<template>
  <div class="iot-panel">
    <div v-if="loading" class="mf-empty">正在读取家里的设备…</div>

    <!-- 未配置：手机直接用；真机靠家里 WiFi 直连中枢 -->
    <template v-else-if="!hubConfigured">
      <div class="mf-card setup-card mf-rise">
        <div class="setup-head">
          <div class="setup-icon">🏠</div>
          <div class="setup-copy">
            <div class="setup-title">家里的设备</div>
            <div class="setup-desc">
              用手机就能操作。没有真实中枢时先用演示；家里已有 Home Assistant 时，请连着家里 WiFi，由手机直接访问中枢。
            </div>
          </div>
        </div>

        <button class="solid-btn demo-primary" :disabled="binding" @click="bindDemo">
          {{ binding ? "准备中…" : "用手机开始（演示设备）" }}
        </button>
        <p class="demo-hint">演示在手机里就能开关空调/电视界面，不依赖其它设备。</p>

        <p v-if="error" class="error-line">{{ error }}</p>

        <details class="advanced-ha" open>
          <summary>家里有中枢（手机连 WiFi 直连）</summary>
          <p class="field-hint setup-wifi-hint">
            请确认手机已连<strong>家里 WiFi</strong>（不要用流量）。云端进不了你家路由器，但手机在家里可以。
          </p>
          <label class="field">
            <span class="field-label">中枢地址</span>
            <input
              v-model="hubForm.baseUrl"
              class="field-input"
              type="url"
              placeholder="http://192.168.1.100:8123"
            />
          </label>
          <label class="field">
            <span class="field-label">访问令牌</span>
            <input
              v-model="hubForm.accessToken"
              class="field-input"
              type="password"
              placeholder="Home Assistant 长效令牌"
            />
          </label>
          <div class="setup-actions">
            <button class="ghost-btn" :disabled="testing" @click="testConnection">
              {{ testing ? "测试中…" : "用手机测试" }}
            </button>
            <button class="solid-btn" :disabled="binding || !connected" @click="bindHub">
              {{ binding ? "连接中…" : "用手机连接" }}
            </button>
          </div>
        </details>

        <details class="advanced-ha">
          <summary>进阶：外出时用家里电脑网关</summary>
          <p class="field-hint setup-wifi-hint">
            人不在家、手机不在家里 WiFi 时，可让家里常开电脑跑网关。日常在家请优先用上面的手机直连。
          </p>
          <button
            class="ghost-btn demo-secondary"
            type="button"
            :disabled="pairingBusy"
            @click="startPairingWizard"
          >
            {{ pairingBusy ? "生成中…" : "生成网关配对码" }}
          </button>
          <div v-if="pairingCode" class="pairing-box">
            <p class="pairing-step-title">把下面配对码交给家里人</p>
            <div class="pairing-code">{{ pairingCode }}</div>
            <ol class="pairing-steps">
              <li>找一台<strong>家里常开</strong>的电脑。</li>
              <li>打开 <code>infra/iot-gateway</code>，双击「启动网关」。</li>
              <li>按提示输入上面这 8 位配对码。</li>
              <li>看到「配对成功」后回到本页稍等。</li>
            </ol>
            <div class="pairing-actions">
              <button class="solid-btn" type="button" @click="copyFamilyGuide">
                {{ copiedGuide ? "已复制，去发给家人" : "复制给家人的说明" }}
              </button>
              <button class="ghost-btn" type="button" :disabled="pairingBusy" @click="createPairingCode">
                重新生成配对码
              </button>
            </div>
          </div>
        </details>
      </div>
    </template>

    <!-- 已配置 -->
    <template v-else>
      <div class="panel-bar">
        <span class="mf-chip">{{ hubModeLabel }}</span>
        <button class="mini-btn" @click="showUnbindConfirm = true">更换中枢</button>
      </div>

      <div v-if="hubIsPhoneLocal && localHaUnreachable" class="offline-banner">
        连不上家里中枢。请把手机连到家里 WiFi 后再下拉刷新。
      </div>
      <div v-else-if="hubIsGateway && !gatewayOnline" class="offline-banner">
        家里的「小助手」还没连上。请让电脑上的「启动网关」窗口保持打开。
      </div>
      <div v-else-if="hubOffline" class="offline-banner">中枢暂时连不上，设备状态可能不是最新的</div>
      <p v-if="error" class="error-line">{{ error }}</p>
      <div v-if="loadError" class="mf-empty">{{ loadError }}</div>

      <template v-else>
        <div v-for="(devices, area) in devicesByArea" :key="area" class="area-block">
          <div class="mf-section">
            <span class="mf-section__title">{{ area }}</span>
            <span class="area-count">{{ devices.length }} 台</span>
          </div>
          <div class="devices-grid">
            <button
              v-for="device in devices"
              :key="device.entityId"
              type="button"
              class="device-card mf-card"
              :class="{ offline: device.isOnline === false, active: isDeviceOn(device) }"
              @click="openDetail(device)"
            >
              <div class="device-top">
                <span class="device-icon">{{ getDeviceIcon(device) }}</span>
                <div class="device-meta">
                  <span class="device-name">{{ device.deviceName || device.entityId }}</span>
                  <span class="device-sub">{{ deviceSubtitle(device) }}</span>
                </div>
                <span
                  v-if="isSwitchable(device)"
                  class="power-btn"
                  :class="{
                    on: isDeviceOn(device),
                    disabled: device.isOnline === false || pending[device.entityId],
                  }"
                  @click.stop="toggle(device)"
                >
                  {{ isDeviceOn(device) ? "开" : "关" }}
                </span>
              </div>
              <div v-if="device.entityType === 'sensor'" class="sensor-value">
                {{ getSensorValue(device) }}
              </div>
              <div v-else-if="device.entityType === 'climate'" class="card-hint">
                {{ climateHint(device) }}
              </div>
              <div v-else-if="device.entityType === 'media_player'" class="card-hint">
                {{ mediaHint(device) }}
              </div>
            </button>
          </div>
        </div>

        <div v-if="!areaKeys.length" class="mf-empty">
          <span class="mf-empty__mark">🛋️</span>
          <span v-if="hubIsPhoneLocal">
            {{ localHaUnreachable ? "请连家里 WiFi 后刷新" : "正在从家里中枢读取设备…" }}
          </span>
          <span v-else-if="hubIsGateway">
            {{ gatewayOnline ? "网关已在线，等待上报设备…" : "网关未连接" }}
          </span>
          <span v-else>还没有设备，稍等片刻或重新连接中枢</span>
        </div>

        <div v-if="scenes.length" class="area-block">
          <div class="mf-section">
            <span class="mf-section__title">场景</span>
          </div>
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

      <!-- 设备明细：电源按钮 + 按类型控件 -->
      <div v-if="detail" class="modal-overlay" @click.self="detail = null">
        <div class="mf-card modal-card detail-card">
          <div class="detail-head">
            <div>
              <p class="modal-title">{{ detail.deviceName || detail.entityId }}</p>
              <p class="modal-desc">{{ deviceTypeLabel(detail) }} · {{ deviceSubtitle(detail) }}</p>
            </div>
            <button
              v-if="isSwitchable(detail)"
              class="power-btn large"
              :class="{ on: isDeviceOn(detail) }"
              :disabled="detail.isOnline === false || pending[detail.entityId]"
              @click="toggle(detail)"
            >
              {{ isDeviceOn(detail) ? "开启" : "关闭" }}
            </button>
          </div>

          <div
            v-if="detail.entityType === 'light' && isDeviceOn(detail) && supportsColorControl(detail)"
            class="brightness-row"
          >
            <div
              class="track"
              @click="jumpBrightness(detail, $event)"
              @touchstart.prevent="startDrag(detail, $event)"
              @touchmove.prevent="moveDrag(detail, $event)"
              @touchend="endDrag(detail)"
              @mousedown="startDrag(detail, $event)"
              @mousemove="moveDrag(detail, $event)"
              @mouseup="endDrag(detail)"
            >
              <div class="track-fill" :style="{ width: brightnessPct(detail) + '%' }"></div>
              <div class="track-knob" :style="{ left: brightnessPct(detail) + '%' }"></div>
            </div>
            <span class="range-num">{{ brightnessPct(detail) }}%</span>
          </div>

          <div v-else-if="detail.entityType === 'climate'" class="control-block">
            <div class="temp-row">
              <button
                class="ghost-btn square"
                :disabled="!isDeviceOn(detail) || pending[detail.entityId]"
                @click="nudgeTemp(-1)"
              >−</button>
              <div class="temp-display">
                <span class="temp-num">{{ getClimateTargetTemp(detail) }}</span>
                <span class="temp-unit">°C</span>
              </div>
              <button
                class="ghost-btn square"
                :disabled="!isDeviceOn(detail) || pending[detail.entityId]"
                @click="nudgeTemp(1)"
              >+</button>
            </div>
            <div class="chip-row">
              <button
                v-for="mode in hvacModes"
                :key="mode.value"
                class="chip-btn"
                :class="{ on: climateMode(detail) === mode.value }"
                :disabled="pending[detail.entityId]"
                @click="setHvacMode(mode.value)"
              >
                {{ mode.label }}
              </button>
            </div>
            <div class="chip-row">
              <button
                v-for="fan in fanModes"
                :key="fan.value"
                class="chip-btn"
                :class="{ on: fanMode(detail) === fan.value }"
                :disabled="!isDeviceOn(detail) || pending[detail.entityId]"
                @click="setFanMode(fan.value)"
              >
                {{ fan.label }}
              </button>
            </div>
          </div>

          <div v-else-if="detail.entityType === 'media_player'" class="control-block">
            <div class="temp-row">
              <button class="ghost-btn square" :disabled="pending[detail.entityId]" @click="nudgeVolume(-5)">−</button>
              <div class="temp-display">
                <span class="temp-num">{{ getMediaVolumePct(detail) }}</span>
                <span class="temp-unit">%</span>
              </div>
              <button class="ghost-btn square" :disabled="pending[detail.entityId]" @click="nudgeVolume(5)">+</button>
            </div>
            <div class="chip-row">
              <button class="chip-btn" @click="toggleMute">
                {{ detail.attributes?.is_volume_muted ? "取消静音" : "静音" }}
              </button>
              <button class="chip-btn" @click="playPause">播放/暂停</button>
            </div>
            <div class="chip-row">
              <button
                v-for="source in mediaSources"
                :key="source"
                class="chip-btn"
                :class="{ on: detail.attributes?.source === source }"
                @click="selectSource(source)"
              >
                {{ source }}
              </button>
            </div>
          </div>

          <div v-else-if="detail.entityType === 'sensor'" class="sensor-value">
            {{ getSensorValue(detail) }}
          </div>

          <div class="modal-actions">
            <button class="ghost-btn" @click="detail = null">关闭</button>
          </div>
        </div>
      </div>

      <div v-if="showUnbindConfirm" class="modal-overlay" @click.self="showUnbindConfirm = false">
        <div class="mf-card modal-card">
          <p class="modal-title">解绑当前中枢？</p>
          <p class="modal-desc">解绑后设备列表会清空，家里的任何成员都可以重新连接。</p>
          <div class="modal-actions">
            <button class="ghost-btn" @click="showUnbindConfirm = false">取消</button>
            <button class="danger-btn" @click="unbindHub">确认解绑</button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { request } from "@app/utils/request";
import {
  getLightBrightness,
  getSensorValue,
  supportsColorControl,
  isDeviceOn,
  isSwitchable,
  getClimateTargetTemp,
  getMediaVolumePct,
  deviceTypeLabel,
} from "./iot-logic.mjs";
import { testHaLocal, fetchHaDevicesForSync, callHaService } from "./ha-local.mjs";

const DEMO_HUB = { baseUrl: "demo://local", accessToken: "demo" };

export default {
  name: "IotPanel",

  data() {
    return {
      loading: true,
      hubConfigured: false,
      hubIsDemo: false,
      hubIsGateway: false,
      hubIsPhoneLocal: false,
      gatewayOnline: false,
      localHaUnreachable: false,
      localHa: null,
      syncingLocal: false,
      hubForm: { baseUrl: "", accessToken: "" },
      connected: false,
      testing: false,
      binding: false,
      pairingBusy: false,
      pairingCode: "",
      pairingExpiresAt: null,
      copiedGuide: false,
      error: "",
      loadError: "",
      devicesByArea: {},
      scenes: [],
      pending: {},
      dragPct: {},
      dragging: null,
      showUnbindConfirm: false,
      detail: null,
      pollTimer: null,
      hvacModes: [
        { value: "cool", label: "制冷" },
        { value: "heat", label: "制热" },
        { value: "dry", label: "除湿" },
        { value: "fan_only", label: "送风" },
        { value: "auto", label: "自动" },
        { value: "off", label: "关闭" },
      ],
      fanModes: [
        { value: "auto", label: "风速自动" },
        { value: "low", label: "低" },
        { value: "medium", label: "中" },
        { value: "high", label: "高" },
      ],
      mediaSources: ["HDMI 1", "HDMI 2", "AV", "电视直播"],
    };
  },

  computed: {
    areaKeys() {
      return Object.keys(this.devicesByArea);
    },
    hubModeLabel() {
      if (this.hubIsDemo) return "手机演示";
      if (this.hubIsPhoneLocal) return this.localHaUnreachable ? "未连家里 WiFi" : "手机直连家里";
      if (this.hubIsGateway) return this.gatewayOnline ? "家庭网关在线" : "家庭网关离线";
      return "已连接中枢";
    },
    hubOffline() {
      if (this.hubIsPhoneLocal) return this.localHaUnreachable;
      if (this.hubIsGateway) return !this.gatewayOnline;
      const all = this.areaKeys.flatMap(area => this.devicesByArea[area] || []);
      return all.length > 0 && all.every(device => device.isOnline === false);
    },
  },

  methods: {
    getLightBrightness,
    getSensorValue,
    supportsColorControl,
    isDeviceOn,
    isSwitchable,
    getClimateTargetTemp,
    getMediaVolumePct,
    deviceTypeLabel,

    getDeviceIcon(device) {
      const icons = {
        light: "💡",
        switch: "🔌",
        sensor: "📊",
        climate: "❄️",
        media_player: "📺",
        scene: "🎬",
      };
      return icons[device.entityType] || "📱";
    },

    deviceSubtitle(device) {
      if (device.isOnline === false) return "离线";
      if (device.entityType === "sensor") return "传感器";
      if (device.entityType === "climate") {
        if (device.state === "off") return "已关闭";
        const modeMap = { cool: "制冷中", heat: "制热中", dry: "除湿中", fan_only: "送风中", auto: "自动" };
        return modeMap[device.state] || "运行中";
      }
      if (device.entityType === "media_player") {
        if (device.state === "playing") return "播放中";
        if (device.state === "paused") return "已暂停";
        if (device.state === "buffering" || device.state === "on") return "已开启";
        if (device.state === "off" || device.state === "idle" || device.state === "standby") return "已关闭";
        return this.isDeviceOn(device) ? "已开启" : "已关闭";
      }
      return device.state === "on" ? "已开启" : "已关闭";
    },

    climateHint(device) {
      if (device.state === "off") return "已关闭 · 点开调节";
      return `${this.getClimateTargetTemp(device)}°C · 点开调节`;
    },

    mediaHint(device) {
      if (!this.isDeviceOn(device)) return "已关闭 · 点开调节";
      const title = device.attributes?.media_title || device.attributes?.source || "影音";
      return `${title} · ${this.getMediaVolumePct(device)}%`;
    },

    climateMode(device) {
      // 关机时以 state 为准，避免 attributes.hvac_mode 残留上次运行模式
      if (device.state === "off") return "off";
      return device.attributes?.hvac_mode || device.state || "off";
    },

    fanMode(device) {
      return device.attributes?.fan_mode || "auto";
    },

    openDetail(device) {
      this.detail = device;
      this.error = "";
    },

    refreshDetail() {
      if (!this.detail) return;
      const all = this.areaKeys.flatMap(area => this.devicesByArea[area] || []);
      const next = all.find(d => d.entityId === this.detail.entityId);
      this.detail = next || null;
    },

    load() {
      return this.init();
    },

    applyHubMeta(hub) {
      this.hubConfigured = !!hub?.configured;
      this.hubIsDemo = !!hub?.demo;
      this.hubIsGateway = !!hub?.gateway;
      this.gatewayOnline = !!hub?.gatewayOnline;
      this.hubIsPhoneLocal = !!hub?.phoneLocal;
      if (hub?.baseUrl && this.hubIsPhoneLocal) {
        this.localHa = {
          ...(this.localHa || {}),
          baseUrl: hub.baseUrl,
          accessToken: this.localHa?.accessToken || "",
        };
      }
    },

    async ensureLocalHaAccess() {
      if (!this.hubIsPhoneLocal) return null;
      if (this.localHa?.baseUrl && this.localHa?.accessToken) return this.localHa;
      const access = await request("/iot/hub/local-access");
      this.localHa = {
        baseUrl: access.baseUrl,
        accessToken: access.accessToken,
      };
      return this.localHa;
    },

    async syncFromPhoneHa() {
      if (this.syncingLocal) return;
      this.syncingLocal = true;
      try {
        const creds = await this.ensureLocalHaAccess();
        const { devices } = await fetchHaDevicesForSync(creds.baseUrl, creds.accessToken);
        await request("/iot/devices/local-sync", {
          method: "PUT",
          data: { devices: devices || [] },
        });
        this.localHaUnreachable = false;
      } finally {
        this.syncingLocal = false;
      }
    },

    async init() {
      this.loading = true;
      this.loadError = "";
      try {
        const hub = await request("/iot/hub");
        this.applyHubMeta(hub);
        if (this.hubConfigured) {
          this.pairingCode = "";
          await this.loadDevices();
        }
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

    /** 等待网关注册时静默轮询，不打 loading。 */
    async pollWhilePairing() {
      if (!this.pairingCode || this.hubConfigured || this.loading || this.pairingBusy) return;
      try {
        const hub = await request("/iot/hub");
        if (!hub?.configured) return;
        this.applyHubMeta(hub);
        this.pairingCode = "";
        await this.loadDevices();
      } catch (e) {
        if (e.status === 401) this.$emit("session-ended");
      }
    },

    async startPairingWizard() {
      this.copiedGuide = false;
      await this.createPairingCode();
    },

    async createPairingCode() {
      this.pairingBusy = true;
      this.error = "";
      this.copiedGuide = false;
      try {
        const vo = await request("/iot/hub/pairing-code", { method: "POST" });
        this.pairingCode = vo?.code || "";
        this.pairingExpiresAt = vo?.expiresAt || null;
        if (!this.pairingCode) this.error = "未能生成配对码";
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.error = e.message || "生成配对码失败";
      } finally {
        this.pairingBusy = false;
      }
    },

    familyGuideText() {
      const code = this.pairingCode || "（请先在 App 生成）";
      return [
        "【家庭设备配对】请帮家里手机 App 装一次「家庭网关」",
        "",
        "1. 用家里常开的电脑打开文件夹：infra/iot-gateway",
        "2. 双击「启动网关」（Windows：启动网关.bat；Mac：启动网关.command）",
        "3. 若提示安装 Node.js，按提示打开官网装 LTS 后，再双击一次",
        `4. 程序问配对码时输入：${code}`,
        "5. 看到「配对成功」后告诉手机这边的人，在「家居」页等几秒",
        "",
        "配对码约 15 分钟有效。日常在家优先用「手机连家里 WiFi 直连中枢」。",
      ].join("\n");
    },

    async copyFamilyGuide() {
      const text = this.familyGuideText();
      try {
        if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(text);
        } else if (typeof uni !== "undefined" && uni.setClipboardData) {
          await new Promise((resolve, reject) => {
            uni.setClipboardData({
              data: text,
              success: resolve,
              fail: reject,
            });
          });
        } else {
          throw new Error("clipboard unavailable");
        }
        this.copiedGuide = true;
        this.error = "";
      } catch {
        this.error = "复制失败，请手动把配对码和步骤发给家人";
      }
    },

    async testConnection() {
      if (!this.hubForm.baseUrl || !this.hubForm.accessToken) {
        this.error = "请先填写中枢地址和访问令牌";
        return;
      }
      this.testing = true;
      this.connected = false;
      this.error = "";
      try {
        // 手机在家里 WiFi 直连探测，不经云端
        await testHaLocal(this.hubForm.baseUrl, this.hubForm.accessToken);
        this.connected = true;
      } catch (e) {
        this.error =
          e.message || "手机连不上中枢。请确认已连家里 WiFi，地址和令牌正确。";
      } finally {
        this.testing = false;
      }
    },

    async bindHub() {
      if (!this.connected) return;
      await this.submitHub({ ...this.hubForm, phoneLocal: true });
    },

    async bindDemo() {
      this.error = "";
      await this.submitHub(DEMO_HUB);
    },

    async submitHub(payload) {
      this.binding = true;
      try {
        const isDemo = payload.baseUrl === DEMO_HUB.baseUrl;
        const hub = await request("/iot/hub", { method: "PUT", data: payload });
        this.applyHubMeta(hub || { configured: true, demo: isDemo, phoneLocal: !isDemo && payload.phoneLocal });
        if (!isDemo && payload.phoneLocal) {
          this.localHa = {
            baseUrl: payload.baseUrl,
            accessToken: payload.accessToken,
          };
          this.hubIsPhoneLocal = true;
        }
        this.connected = false;
        this.hubForm = { baseUrl: "", accessToken: "" };
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "绑定失败，请检查地址和令牌";
      } finally {
        this.binding = false;
      }
    },

    async unbindHub() {
      this.showUnbindConfirm = false;
      this.detail = null;
      try {
        await request("/iot/hub", { method: "DELETE" });
        this.hubConfigured = false;
        this.hubIsDemo = false;
        this.hubIsGateway = false;
        this.hubIsPhoneLocal = false;
        this.gatewayOnline = false;
        this.localHa = null;
        this.localHaUnreachable = false;
        this.devicesByArea = {};
        this.scenes = [];
      } catch (e) {
        this.error = e.message || "解绑失败";
      }
    },

    async loadDevices() {
      this.loadError = "";
      try {
        if (this.hubIsPhoneLocal) {
          try {
            await this.syncFromPhoneHa();
          } catch {
            this.localHaUnreachable = true;
            // 仍展示云端上次快照，并提示连家里 WiFi
          }
        }
        const tasks = [request("/iot/devices"), request("/iot/scenes")];
        if (this.hubIsGateway) {
          tasks.push(request("/iot/hub").catch(() => null));
        }
        const [areas, scenes, hub] = await Promise.all(tasks);
        if (hub) {
          this.gatewayOnline = !!hub.gatewayOnline;
          this.hubIsGateway = !!hub.gateway || this.hubIsGateway;
        }
        const grouped = {};
        for (const group of areas || []) {
          grouped[group.areaName || "未分组"] = group.devices || [];
        }
        this.devicesByArea = grouped;
        this.scenes = scenes || [];
        this.refreshDetail();
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "设备加载失败";
      }
    },

    async toggle(device) {
      if (device.isOnline === false || this.pending[device.entityId]) return;
      const command = this.isDeviceOn(device) ? "turn_off" : "turn_on";
      await this.executeCommand(device, command);
    },

    async nudgeTemp(delta) {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      const next = Math.min(35, Math.max(16, this.getClimateTargetTemp(this.detail) + delta));
      this.detail = {
        ...this.detail,
        attributes: { ...(this.detail.attributes || {}), temperature: next },
      };
      await this.executeCommand(this.detail, "set_temperature", { temperature: next });
    },

    async setHvacMode(mode) {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      await this.executeCommand(this.detail, "set_hvac_mode", { hvac_mode: mode });
    },

    async setFanMode(mode) {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      await this.executeCommand(this.detail, "set_fan_mode", { fan_mode: mode });
    },

    async nudgeVolume(delta) {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      const next = Math.min(100, Math.max(0, this.getMediaVolumePct(this.detail) + delta));
      this.detail = {
        ...this.detail,
        attributes: {
          ...(this.detail.attributes || {}),
          volume_level: next / 100,
          is_volume_muted: false,
        },
      };
      await this.executeCommand(this.detail, "volume_set", { volume_level: next / 100 });
    },

    async toggleMute() {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      const muted = !!this.detail.attributes?.is_volume_muted;
      await this.executeCommand(this.detail, "volume_mute", { is_volume_muted: !muted });
    },

    async playPause() {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      await this.executeCommand(this.detail, "media_play_pause");
    },

    async selectSource(source) {
      if (!this.detail || this.pending[this.detail.entityId]) return;
      await this.executeCommand(this.detail, "select_source", { source });
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

    async executeCommand(device, commandType, parameters = null) {
      this.pending = { ...this.pending, [device.entityId]: true };
      this.error = "";
      try {
        if (this.hubIsPhoneLocal) {
          const creds = await this.ensureLocalHaAccess();
          const domain = device.entityType;
          const data = { entity_id: device.entityId, ...(parameters || {}) };
          await callHaService(creds.baseUrl, creds.accessToken, domain, commandType, data);
        } else {
          await request(`/iot/devices/${device.entityId}/command`, {
            method: "POST",
            data: { commandType, parameters },
          });
        }
        await this.loadDevices();
      } catch (e) {
        if (this.hubIsPhoneLocal) this.localHaUnreachable = true;
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
        if (this.hubIsPhoneLocal) {
          const creds = await this.ensureLocalHaAccess();
          await callHaService(creds.baseUrl, creds.accessToken, "scene", "turn_on", {
            entity_id: scene.entityId,
          });
        } else {
          await request(`/iot/scenes/${scene.entityId}/activate`, { method: "POST" });
        }
        await this.loadDevices();
      } catch (e) {
        if (this.hubIsPhoneLocal) this.localHaUnreachable = true;
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
    this.pollTimer = setInterval(() => {
      if (this.loading || this.syncingLocal) return;
      if (this.hubConfigured) this.loadDevices();
      else if (this.pairingCode) this.pollWhilePairing();
    }, 5000);
  },

  beforeUnmount() {
    if (this.pollTimer) clearInterval(this.pollTimer);
  },
};
</script>

<style scoped>
.iot-panel {
  padding: 4px 0 24px;
}

.setup-card {
  padding: 22px 20px;
}

.setup-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin-bottom: 20px;
}

.setup-icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  flex: none;
  border-radius: 26px;
  font-size: 24px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: var(--sh-1);
}

.setup-title {
  font-size: 17px;
  font-weight: 650;
  color: var(--c-ink);
}

.setup-desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
}

.demo-primary {
  width: 100%;
  height: 48px;
  border: none;
  border-radius: 30px;
  font-size: 15px;
  font-weight: 650;
}

.demo-secondary {
  width: 100%;
  height: 44px;
  margin-top: 12px;
  border-radius: 22px;
  font-size: 14px;
}

.pairing-box {
  margin-top: 14px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid var(--c-glass-line);
}

.pairing-step-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--c-ink-2);
  text-align: center;
}

.pairing-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-align: center;
  color: var(--c-ink);
}

.pairing-steps {
  margin: 12px 0 0;
  padding-left: 1.2em;
  font-size: 12px;
  line-height: 1.75;
  color: var(--c-muted);
}

.pairing-steps code {
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.06);
}

.pairing-actions {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.pairing-actions .solid-btn,
.pairing-actions .ghost-btn {
  width: 100%;
  height: 44px;
  border-radius: 22px;
}

.pairing-hint {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
  word-break: break-all;
}

.pairing-hint code {
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.06);
}

.pairing-tech {
  margin-top: 12px;
  padding-top: 10px;
}

.demo-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
  text-align: center;
}

.setup-wifi-hint {
  display: block;
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
}

.advanced-ha {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--c-line);
}

.advanced-ha summary {
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink-2);
  margin-bottom: 12px;
}

.field {
  display: block;
  margin-bottom: 14px;
}

.field-label {
  display: block;
  margin-bottom: 7px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-ink-2);
}

.field-input {
  width: 100%;
  height: 46px;
  padding: 0 16px;
  box-sizing: border-box;
  font-size: 14px;
  color: var(--c-ink);
  background: rgba(255, 255, 255, 0.66);
  border: 1px solid var(--c-glass-line);
  border-radius: 16px;
  outline: none;
}

.field-hint {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--c-muted);
}

.setup-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}

.setup-actions button {
  flex: 1;
  border: none;
  border-radius: 30px;
  font-size: 14px;
  font-weight: 600;
  height: 46px;
}

.ghost-btn {
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid var(--c-line);
}

.solid-btn {
  color: #f4f7ff;
  background: #4f7dc6;
  box-shadow: 0 8px 20px rgba(134, 187, 255, 0.45);
}

button[disabled] {
  opacity: 0.5;
}

.error-line {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--c-danger);
}

.panel-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.mini-btn {
  height: 34px;
  padding: 0 16px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid var(--c-line);
  border-radius: 999px;
}

.offline-banner {
  margin-bottom: 14px;
  padding: 11px 14px;
  border-radius: 16px;
  font-size: 12px;
  color: #96702f;
  background: var(--c-sand-soft);
  border: 1px solid rgba(207, 167, 105, 0.34);
}

.area-block {
  margin-bottom: 8px;
}

.area-count {
  font-size: 11px;
  color: var(--c-muted);
}

.devices-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.device-card {
  display: block;
  width: 100%;
  text-align: left;
  padding: 15px 16px;
  border-radius: 24px;
  border: none;
  cursor: pointer;
  transition: transform 220ms var(--e-out), box-shadow 220ms var(--e-out);
}

.device-card.active {
  box-shadow: 0 12px 26px rgba(79, 125, 198, 0.26);
}

.device-card.offline {
  opacity: 0.6;
}

.device-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.device-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 20px;
  font-size: 19px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: var(--sh-1);
}

.device-meta {
  flex: 1;
  min-width: 0;
}

.device-name {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-sub {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--c-muted);
}

.power-btn {
  flex: none;
  min-width: 44px;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  font-weight: 700;
  color: var(--c-ink-2);
}

.power-btn.on {
  background: #4f7dc6;
  border-color: rgba(79, 125, 198, 0.7);
  color: #f4f7ff;
}

.power-btn.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.power-btn.large {
  height: 40px;
  min-width: 72px;
  font-size: 13px;
}

.card-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--c-muted);
}

.brightness-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}

.track {
  position: relative;
  flex: 1;
  height: 28px;
  cursor: pointer;
  touch-action: none;
}

.track::before {
  content: "";
  position: absolute;
  top: 12px;
  left: 0;
  right: 0;
  height: 4px;
  border-radius: 999px;
  background: rgba(79, 125, 198, 0.18);
}

.track-fill {
  position: absolute;
  top: 12px;
  left: 0;
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(90deg, #86bbff, #4f7dc6);
}

.track-knob {
  position: absolute;
  top: 6px;
  width: 16px;
  height: 16px;
  margin-left: -8px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #4f7dc6;
  box-sizing: border-box;
}

.range-num {
  min-width: 38px;
  text-align: right;
  font-size: 11px;
  color: var(--c-muted);
  font-variant-numeric: tabular-nums;
}

.sensor-value {
  margin-top: 12px;
  padding: 9px 12px;
  border-radius: 14px;
  font-size: 15px;
  font-weight: 600;
  text-align: center;
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.6);
  font-variant-numeric: tabular-nums;
}

.control-block {
  margin-top: 16px;
}

.temp-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 14px;
}

.temp-display {
  min-width: 88px;
  text-align: center;
}

.temp-num {
  font-size: 36px;
  font-weight: 700;
  color: var(--c-ink);
  font-variant-numeric: tabular-nums;
}

.temp-unit {
  margin-left: 4px;
  font-size: 14px;
  color: var(--c-muted);
}

.ghost-btn.square {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  font-size: 22px;
  font-weight: 600;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.chip-btn {
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.66);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-ink-2);
}

.chip-btn.on {
  background: #4f7dc6;
  border-color: rgba(79, 125, 198, 0.7);
  color: #f4f7ff;
}

.scenes-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.scene-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-primary-deep);
  background: var(--c-primary-soft);
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: var(--sh-1);
}

.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(20, 43, 83, 0.34);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.modal-card {
  width: min(360px, 100%);
  padding: 22px 20px;
}

.detail-card {
  width: min(420px, 100%);
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.modal-title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  color: var(--c-ink);
}

.modal-desc {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
}

.modal-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.modal-actions button {
  flex: 1;
  height: 44px;
  border-radius: 30px;
  font-size: 14px;
  font-weight: 600;
}

.danger-btn {
  color: #fff;
  background: var(--c-danger);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: 0 8px 18px rgba(185, 106, 91, 0.32);
}
</style>
