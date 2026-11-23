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
            <div class="setup-title">连接家里的智能中枢</div>
            <div class="setup-desc">
              支持 Home Assistant。连上以后，家里每个人都能直接开关灯、插座和场景。
            </div>
          </div>
        </div>

        <label class="field">
          <span class="field-label">中枢地址</span>
          <input
            v-model="hubForm.baseUrl"
            class="field-input"
            type="url"
            placeholder="http://192.168.1.100:8123"
          />
          <span class="field-hint">仅支持私网地址或 localhost</span>
        </label>

        <label class="field">
          <span class="field-label">访问令牌</span>
          <input
            v-model="hubForm.accessToken"
            class="field-input"
            type="password"
            placeholder="Home Assistant 里创建的长效令牌"
          />
        </label>

        <div class="setup-actions">
          <button class="ghost-btn" :disabled="testing" @click="testConnection">
            {{ testing ? "测试中…" : "测试连接" }}
          </button>
          <button class="solid-btn" :disabled="binding || !connected" @click="bindHub">
            {{ binding ? "绑定中…" : "绑定中枢" }}
          </button>
        </div>

        <p v-if="error" class="error-line">{{ error }}</p>

        <div class="demo-block">
          <span class="demo-hint">没有中枢设备？先用演示设备体验，家里任何人都能操作。</span>
          <button class="ghost-btn demo-btn" :disabled="binding" @click="bindDemo">
            {{ binding ? "准备中…" : "用演示设备体验" }}
          </button>
        </div>
      </div>
    </template>

    <!-- 已配置中枢 -->
    <template v-else>
      <div class="panel-bar">
        <span class="mf-chip">{{ hubIsDemo ? "演示设备" : "已连接中枢" }}</span>
        <button class="mini-btn" @click="showUnbindConfirm = true">更换中枢</button>
      </div>

      <div v-if="hubOffline" class="offline-banner">⚠️ 中枢暂时连不上，设备状态可能不是最新的</div>
      <p v-if="error" class="error-line">{{ error }}</p>

      <div v-if="loadError" class="mf-empty">{{ loadError }}</div>

      <template v-else>
        <div v-for="(devices, area) in devicesByArea" :key="area" class="area-block">
          <div class="mf-section">
            <span class="mf-section__title">{{ area }}</span>
            <span class="area-count">{{ devices.length }} 台</span>
          </div>
          <div class="devices-grid">
            <div
              v-for="device in devices"
              :key="device.entityId"
              class="device-card mf-card"
              :class="{ offline: device.isOnline === false, active: device.state === 'on' }"
            >
              <div class="device-top">
                <span class="device-icon">{{ getDeviceIcon(device) }}</span>
                <div class="device-meta">
                  <span class="device-name">{{ device.deviceName || device.entityId }}</span>
                  <span class="device-sub">{{ deviceSubtitle(device) }}</span>
                </div>
                <button
                  v-if="device.entityType === 'light' || device.entityType === 'switch'"
                  class="switch-pill"
                  :class="{ on: device.state === 'on' }"
                  :disabled="device.isOnline === false || pending[device.entityId]"
                  @click="toggle(device)"
                >
                  <span class="knob"></span>
                </button>
              </div>

              <div
                v-if="device.entityType === 'light' && device.state === 'on' && supportsColorControl(device)"
                class="brightness-row"
              >
                <!-- 不用 <input type="range">：uni-app 会把它编译成文本框，根本没有滑块 -->
                <div
                  class="track"
                  @click="jumpBrightness(device, $event)"
                  @touchstart.prevent="startDrag(device, $event)"
                  @touchmove.prevent="moveDrag(device, $event)"
                  @touchend="endDrag(device)"
                  @mousedown="startDrag(device, $event)"
                  @mousemove="moveDrag(device, $event)"
                  @mouseup="endDrag(device)"
                >
                  <div class="track-fill" :style="{ width: brightnessPct(device) + '%' }"></div>
                  <div class="track-knob" :style="{ left: brightnessPct(device) + '%' }"></div>
                </div>
                <span class="range-num">{{ brightnessPct(device) }}%</span>
              </div>
              <div v-else-if="device.entityType === 'sensor'" class="sensor-value">
                {{ getSensorValue(device) }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="!areaKeys.length" class="mf-empty">
          <span class="mf-empty__mark">🛋️</span>
          <span>还没有设备，稍等片刻或重新连接中枢</span>
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
import { getLightBrightness, getSensorValue, supportsColorControl } from "./iot-logic.mjs";

// 智能家居对家庭成员开放：任何已登录成员都可以连接中枢、控制设备、触发场景。
// 后端 /api/iot/** 只校验登录身份，不再区分管理员与成员。
const DEMO_HUB = { baseUrl: "demo://local", accessToken: "demo" };

export default {
  name: "IotPanel",

  data() {
    return {
      loading: true,
      hubConfigured: false,
      hubIsDemo: false,
      hubForm: { baseUrl: "", accessToken: "" },
      connected: false,
      testing: false,
      binding: false,
      error: "",
      loadError: "",
      devicesByArea: {},
      scenes: [],
      pending: {},
      dragPct: {},
      dragging: null,
      showUnbindConfirm: false,
      pollTimer: null,
    };
  },

  computed: {
    areaKeys() {
      return Object.keys(this.devicesByArea);
    },
    hubOffline() {
      const all = this.areaKeys.flatMap(area => this.devicesByArea[area] || []);
      return all.length > 0 && all.every(device => device.isOnline === false);
    },
  },

  methods: {
    getLightBrightness,
    getSensorValue,
    supportsColorControl,

    getDeviceIcon(device) {
      const icons = { light: "💡", switch: "🔌", sensor: "📊", scene: "🎬" };
      return icons[device.entityType] || "📱";
    },

    deviceSubtitle(device) {
      if (device.isOnline === false) return "离线";
      if (device.entityType === "sensor") return "传感器";
      return device.state === "on" ? "已开启" : "已关闭";
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
        if (this.hubConfigured) await this.loadDevices();
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

    async testConnection() {
      if (!this.hubForm.baseUrl || !this.hubForm.accessToken) {
        this.error = "请先填写中枢地址和访问令牌";
        return;
      }
      this.testing = true;
      this.connected = false;
      this.error = "";
      try {
        await request("/iot/hub/test", { method: "POST", data: this.hubForm });
        this.connected = true;
      } catch (e) {
        this.error = e.message || "连接失败，请检查地址和令牌";
      } finally {
        this.testing = false;
      }
    },

    async bindHub() {
      if (!this.connected) return;
      await this.submitHub(this.hubForm);
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
      try {
        await request("/iot/hub", { method: "DELETE" });
        this.hubConfigured = false;
        this.hubIsDemo = false;
        this.devicesByArea = {};
        this.scenes = [];
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
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "设备加载失败";
      }
    },

    async toggle(device) {
      const command = device.state === "on" ? "turn_off" : "turn_on";
      await this.executeCommand(device, command);
    },

    /** 拖动中的乐观百分比：没有它，滑一下要等一次请求才动。 */
    brightnessPct(device) {
      const dragging = this.dragPct[device.entityId];
      return dragging === undefined ? getLightBrightness(device) : dragging;
    },

    /** 把点击/拖动位置换算成 0–100。track 的 currentTarget 才是滑块本体。 */
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
      // 拖拽结束时浏览器还会补一次 click，别把刚设好的亮度又按旧位置覆盖
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
        await request(`/iot/devices/${device.entityId}/command`, {
          method: "POST",
          data: { commandType, parameters },
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
    // 状态轮询：中枢那边可能有别的家人或自动化在改设备
    this.pollTimer = setInterval(() => {
      if (this.hubConfigured && !this.loading) this.loadDevices();
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

/* ---------- 未配置：连接中枢 ---------- */
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

.field-input:focus {
  border-color: rgba(79, 125, 198, 0.42);
  box-shadow: 0 0 0 3px rgba(134, 187, 255, 0.22);
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
  margin-top: 18px;
}

.setup-actions button {
  flex: 1;
  border: none;
  border-radius: 30px;
  font-size: 14px;
  font-weight: 600;
  height: 46px;
  letter-spacing: 0.02em;
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

.demo-block {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--c-line);
  text-align: center;
}

.demo-hint {
  display: block;
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
}

.demo-btn {
  height: 40px;
  padding: 0 22px;
  border-radius: 30px;
  font-size: 13px;
  font-weight: 600;
}

/* ---------- 已配置 ---------- */
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
  padding: 15px 16px;
  border-radius: 24px;
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

/* 胶囊开关 */
.switch-pill {
  position: relative;
  flex: none;
  width: 52px;
  height: 30px;
  min-height: 30px;
  padding: 0;
  border-radius: 999px;
  border: 1px solid var(--c-line);
  background: rgba(255, 255, 255, 0.7);
  transition: background 220ms var(--e-out), border-color 220ms var(--e-out);
}

.switch-pill .knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 6px rgba(20, 43, 83, 0.18);
  transition: transform 240ms var(--e-spring);
}

.switch-pill.on {
  background: #4f7dc6;
  border-color: rgba(79, 125, 198, 0.7);
}

.switch-pill.on .knob {
  transform: translateX(22px);
}

.brightness-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
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
  box-shadow: 0 3px 8px rgba(79, 125, 198, 0.35);
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

/* ---------- 解绑确认 ---------- */
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
