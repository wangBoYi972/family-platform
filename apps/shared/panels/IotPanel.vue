<template>
  <div class="iot-panel">
    <div v-if="loading" class="mf-empty">正在读取家里的设备…</div>

    <!-- 未配置：真控走家庭网关，演示与 HA 次之 -->
    <template v-else-if="!hubConfigured">
      <div class="mf-card setup-card mf-rise">
        <div class="setup-head">
          <div class="setup-icon">🏠</div>
          <div class="setup-copy">
            <div class="setup-title">家里的设备</div>
            <div class="setup-desc">
              想控制真实空调/电视：在家里电脑或树莓派跑网关进程，用配对码连上。也可先用演示设备熟悉界面。
            </div>
          </div>
        </div>

        <button class="solid-btn demo-primary" :disabled="pairingBusy" @click="createPairingCode">
          {{ pairingBusy ? "生成中…" : "配对家庭网关（真机控制）" }}
        </button>
        <div v-if="pairingCode" class="pairing-box">
          <div class="pairing-code">{{ pairingCode }}</div>
          <p class="pairing-hint">
            15 分钟内有效。在家里机器执行：
            <code>PAIRING_CODE={{ pairingCode }} npm start</code>
            （目录 <code>infra/iot-gateway</code>）
          </p>
          <button class="ghost-btn" :disabled="pairingBusy" @click="createPairingCode">重新生成</button>
        </div>

        <button class="ghost-btn demo-secondary" :disabled="binding" @click="bindDemo">
          {{ binding ? "准备中…" : "先用演示设备体验" }}
        </button>
        <p class="demo-hint">演示不依赖家里硬件；真控需网关在线并上报设备。</p>

        <p v-if="error" class="error-line">{{ error }}</p>

        <details class="advanced-ha">
          <summary>我有 Home Assistant，连接真实中枢</summary>
          <label class="field">
            <span class="field-label">中枢地址</span>
            <input
              v-model="hubForm.baseUrl"
              class="field-input"
              type="url"
              placeholder="http://192.168.1.100:8123"
            />
            <span class="field-hint">仅支持私网地址或 localhost；云端无法扫描你家局域网</span>
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
              {{ testing ? "测试中…" : "测试连接" }}
            </button>
            <button class="solid-btn" :disabled="binding || !connected" @click="bindHub">
              {{ binding ? "绑定中…" : "绑定中枢" }}
            </button>
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

      <div v-if="hubIsGateway && !gatewayOnline" class="offline-banner">
        家庭网关未在线，请确认家里的网关进程已启动
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
                  :class="{ on: isDeviceOn(device) }"
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
          <span v-if="hubIsGateway">
            {{ gatewayOnline ? "网关已在线，等待上报设备…" : "网关未连接，请在家里启动网关进程" }}
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
              <button class="ghost-btn square" :disabled="!isDeviceOn(detail)" @click="nudgeTemp(-1)">−</button>
              <div class="temp-display">
                <span class="temp-num">{{ getClimateTargetTemp(detail) }}</span>
                <span class="temp-unit">°C</span>
              </div>
              <button class="ghost-btn square" :disabled="!isDeviceOn(detail)" @click="nudgeTemp(1)">+</button>
            </div>
            <div class="chip-row">
              <button
                v-for="mode in hvacModes"
                :key="mode.value"
                class="chip-btn"
                :class="{ on: climateMode(detail) === mode.value }"
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
                :disabled="!isDeviceOn(detail)"
                @click="setFanMode(fan.value)"
              >
                {{ fan.label }}
              </button>
            </div>
          </div>

          <div v-else-if="detail.entityType === 'media_player'" class="control-block">
            <div class="temp-row">
              <button class="ghost-btn square" @click="nudgeVolume(-5)">−</button>
              <div class="temp-display">
                <span class="temp-num">{{ getMediaVolumePct(detail) }}</span>
                <span class="temp-unit">%</span>
              </div>
              <button class="ghost-btn square" @click="nudgeVolume(5)">+</button>
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

const DEMO_HUB = { baseUrl: "demo://local", accessToken: "demo" };

export default {
  name: "IotPanel",

  data() {
    return {
      loading: true,
      hubConfigured: false,
      hubIsDemo: false,
      hubIsGateway: false,
      gatewayOnline: false,
      hubForm: { baseUrl: "", accessToken: "" },
      connected: false,
      testing: false,
      binding: false,
      pairingBusy: false,
      pairingCode: "",
      pairingExpiresAt: null,
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
      if (this.hubIsDemo) return "演示设备";
      if (this.hubIsGateway) return this.gatewayOnline ? "家庭网关在线" : "家庭网关离线";
      return "已连接中枢";
    },
    hubOffline() {
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
        return "已关闭";
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

    async init() {
      this.loading = true;
      this.loadError = "";
      try {
        const hub = await request("/iot/hub");
        this.hubConfigured = !!hub?.configured;
        this.hubIsDemo = !!hub?.demo;
        this.hubIsGateway = !!hub?.gateway;
        this.gatewayOnline = !!hub?.gatewayOnline;
        if (this.hubConfigured) {
          this.pairingCode = "";
          await this.loadDevices();
        } else if (this.pairingCode) {
          // 配对码已生成：轮询等待网关注册成功
          const again = await request("/iot/hub");
          if (again?.configured) {
            this.hubConfigured = true;
            this.hubIsGateway = !!again.gateway;
            this.gatewayOnline = !!again.gatewayOnline;
            this.pairingCode = "";
            await this.loadDevices();
          }
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

    async createPairingCode() {
      this.pairingBusy = true;
      this.error = "";
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
        this.hubIsGateway = false;
        this.gatewayOnline = false;
        this.pairingCode = "";
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
        this.gatewayOnline = false;
        this.pairingCode = "";
        this.devicesByArea = {};
        this.scenes = [];
      } catch (e) {
        this.error = e.message || "解绑失败";
      }
    },

    async loadDevices() {
      this.loadError = "";
      try {
        const tasks = [
          request("/iot/devices"),
          request("/iot/scenes"),
        ];
        if (this.hubIsGateway) {
          tasks.push(request("/iot/gateway/status").catch(() => null));
        }
        const [areas, scenes, status] = await Promise.all(tasks);
        if (status) {
          this.gatewayOnline = !!status.online;
          this.hubIsGateway = !!status.gateway || this.hubIsGateway;
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
      const command = this.isDeviceOn(device) ? "turn_off" : "turn_on";
      await this.executeCommand(device, command);
    },

    async nudgeTemp(delta) {
      if (!this.detail) return;
      const next = Math.min(35, Math.max(16, this.getClimateTargetTemp(this.detail) + delta));
      await this.executeCommand(this.detail, "set_temperature", { temperature: next });
    },

    async setHvacMode(mode) {
      if (!this.detail) return;
      await this.executeCommand(this.detail, "set_hvac_mode", { hvac_mode: mode });
    },

    async setFanMode(mode) {
      if (!this.detail) return;
      await this.executeCommand(this.detail, "set_fan_mode", { fan_mode: mode });
    },

    async nudgeVolume(delta) {
      if (!this.detail) return;
      const next = Math.min(100, Math.max(0, this.getMediaVolumePct(this.detail) + delta));
      await this.executeCommand(this.detail, "volume_set", { volume_level: next / 100 });
    },

    async toggleMute() {
      if (!this.detail) return;
      const muted = !!this.detail.attributes?.is_volume_muted;
      await this.executeCommand(this.detail, "volume_mute", { is_volume_muted: !muted });
    },

    async playPause() {
      if (!this.detail) return;
      await this.executeCommand(this.detail, "media_play_pause");
    },

    async selectSource(source) {
      if (!this.detail) return;
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
    this.pollTimer = setInterval(() => {
      if (this.loading) return;
      if (this.hubConfigured) this.loadDevices();
      else if (this.pairingCode) this.init();
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

.pairing-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-align: center;
  color: var(--c-ink);
}

.pairing-hint {
  margin: 10px 0 12px;
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

.demo-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-muted);
  text-align: center;
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
