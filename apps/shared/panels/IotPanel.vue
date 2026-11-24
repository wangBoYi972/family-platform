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
              支持 Home Assistant。连上以后，家里每个人都能控制灯、空调、电视和音响。
              不在家时可用 Tailscale / HA Cloud 等远程地址（需在服务端加入白名单）。
            </div>
          </div>
        </div>

        <label class="field">
          <span class="field-label">中枢地址</span>
          <input v-model="hubForm.baseUrl" class="field-input" type="url" placeholder="http://192.168.1.100:8123" />
          <span class="field-hint">局域网私网地址，或已加入远程白名单的域名</span>
        </label>

        <label class="field">
          <span class="field-label">访问令牌</span>
          <input v-model="hubForm.accessToken" class="field-input" type="password" placeholder="Home Assistant 里创建的长效令牌" />
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
          <span class="demo-hint">没有中枢设备？先用演示设备体验，或直接手动添加设备。</span>
          <div class="demo-actions">
            <button class="ghost-btn demo-btn" :disabled="binding" @click="bindDemo">
              {{ binding ? "准备中…" : "用演示设备体验" }}
            </button>
            <button class="solid-btn demo-btn" :disabled="binding" @click="openAddDialog">手动添加设备</button>
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
              <div v-if="isManual(device)" class="card-manual">手动</div>
            </div>
          </div>
        </div>

        <div v-if="!areaKeys.length" class="mf-empty">
          <span class="mf-empty__mark">🛋️</span>
          <span>还没有设备，点「添加设备」或连接中枢</span>
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
            class="switch-pill"
            :class="{ on: isOn(detailDevice) }"
            :disabled="pending[detailDevice.entityId]"
            @click="toggle(detailDevice)"
          >
            <span class="knob"></span>
          </button>
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

        <!-- 手动设备：编辑 / 删除 -->
        <div v-if="isManual(detailDevice)" class="detail-manual-actions">
          <button class="ghost-btn" @click="openEditDialog(detailDevice)">编辑设备</button>
          <button class="danger-btn" @click="confirmDelete(detailDevice)">删除设备</button>
        </div>

        <div class="detail-footnote">点空白处或「返回」关闭明细</div>
      </div>
    </div>

    <!-- 添加 / 编辑设备弹窗 -->
    <div v-if="showDeviceDialog" class="modal-overlay" @click.self="closeDeviceDialog">
      <div class="mf-card modal-card device-dialog">
        <p class="modal-title">{{ editingDevice ? '编辑设备' : '添加设备' }}</p>
        <p class="modal-desc">手动设备保存在服务端，不依赖中枢，任何网络下都能操作。</p>

        <label class="field">
          <span class="field-label">设备名称</span>
          <input v-model="deviceForm.deviceName" class="field-input" placeholder="如：客厅空调" maxlength="64" />
        </label>

        <label class="field">
          <span class="field-label">设备品牌</span>
          <input v-model="deviceForm.brand" class="field-input" placeholder="如：格力 / 小米 / Sony（可留空）" maxlength="64" />
        </label>

        <label class="field">
          <span class="field-label">设备类型</span>
          <select v-model="deviceForm.entityType" class="field-input" :disabled="!!editingDevice">
            <option v-for="t in deviceTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </label>

        <label class="field">
          <span class="field-label">所在区域</span>
          <input v-model="deviceForm.areaName" class="field-input" placeholder="如：客厅（留空=自定义）" maxlength="32" />
        </label>

        <p v-if="dialogError" class="error-line">{{ dialogError }}</p>

        <div class="modal-actions">
          <button class="ghost-btn" @click="closeDeviceDialog">取消</button>
          <button class="solid-btn" :disabled="savingDevice" @click="saveDevice">
            {{ savingDevice ? "保存中…" : "保存" }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deletingDevice" class="modal-overlay" @click.self="deletingDevice = null">
      <div class="mf-card modal-card">
        <p class="modal-title">删除「{{ deletingDevice.deviceName }}」？</p>
        <p class="modal-desc">删除后无法恢复，命令历史会保留。</p>
        <div class="modal-actions">
          <button class="ghost-btn" @click="deletingDevice = null">取消</button>
          <button class="danger-btn" :disabled="pending[deletingDevice.entityId]" @click="doDelete">确认删除</button>
        </div>
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

const DEVICE_TYPES = [
  { value: "climate", label: "空调 / 温控" },
  { value: "media_player", label: "电视 / 音响" },
  { value: "light", label: "灯" },
  { value: "switch", label: "插座 / 开关" },
  { value: "sensor", label: "传感器（仅查看）" },
];

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
      deviceTypes: DEVICE_TYPES,
      detailDevice: null,
      showDeviceDialog: false,
      editingDevice: null,
      savingDevice: false,
      dialogError: "",
      deletingDevice: null,
      deviceForm: { deviceName: "", brand: "", entityType: "climate", areaName: "" },
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

    isManual(device) {
      return device.deviceSource === "MANUAL";
    },

    isSwitchable(device) {
      return device.entityType === "light" || device.entityType === "switch";
    },

    brandFallback(device) {
      return BRAND_FALLBACK[device.entityType] || "智能设备";
    },

    statusText(device) {
      if (device.isOnline === false) return "离线";
      if (this.isManual(device) && device.areaName === "自定义" && !device.brand) return "已添加";
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

    // -------- 手动设备 CRUD --------
    openAddDialog() {
      this.editingDevice = null;
      this.deviceForm = { deviceName: "", brand: "", entityType: "climate", areaName: "" };
      this.dialogError = "";
      this.showDeviceDialog = true;
    },

    openEditDialog(device) {
      this.editingDevice = device;
      this.deviceForm = {
        deviceName: device.deviceName || "",
        brand: device.brand || "",
        entityType: device.entityType || "climate",
        areaName: device.areaName || "",
      };
      this.dialogError = "";
      this.showDeviceDialog = true;
    },

    closeDeviceDialog() {
      this.showDeviceDialog = false;
      this.editingDevice = null;
    },

    async saveDevice() {
      if (!this.deviceForm.deviceName || !this.deviceForm.deviceName.trim()) {
        this.dialogError = "请填写设备名称";
        return;
      }
      this.savingDevice = true;
      this.dialogError = "";
      try {
        if (this.editingDevice) {
          await request(`/iot/devices/${this.editingDevice.entityId}`, {
            method: "PUT", data: this.deviceForm,
          });
        } else {
          await request("/iot/devices", { method: "POST", data: this.deviceForm });
        }
        this.closeDeviceDialog();
        this.detailDevice = null;
        await this.loadDevices();
      } catch (e) {
        this.dialogError = e.message || "保存失败";
      } finally {
        this.savingDevice = false;
      }
    },

    confirmDelete(device) {
      this.deletingDevice = device;
    },

    async doDelete() {
      const device = this.deletingDevice;
      if (!device) return;
      this.pending = { ...this.pending, [device.entityId]: true };
      try {
        await request(`/iot/devices/${device.entityId}`, { method: "DELETE" });
        this.deletingDevice = null;
        this.detailDevice = null;
        await this.loadDevices();
      } catch (e) {
        this.error = e.message || "删除失败";
      } finally {
        const next = { ...this.pending };
        delete next[device.entityId];
        this.pending = next;
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

/* ---------- 开关 ---------- */
.switch-pill { width: 46px; height: 26px; flex: none; border-radius: 13px; border: none; background: rgba(120, 130, 150, 0.28); position: relative; }
.switch-pill .knob {
  position: absolute; top: 3px; left: 3px; width: 20px; height: 20px;
  border-radius: 50%; background: #fff; box-shadow: 0 1px 4px rgba(0, 0, 0, 0.2); transition: left 0.2s;
}
.switch-pill.on { background: #4f7dc6; }
.switch-pill.on .knob { left: 23px; }

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
