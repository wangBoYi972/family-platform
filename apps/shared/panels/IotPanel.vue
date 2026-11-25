<template>
  <div class="iot-panel">
    <div v-if="loading" class="mf-empty">正在打开智能遥控…</div>

    <template v-else>
      <div class="remote-head">
        <div>
          <h2 class="remote-title">智能遥控</h2>
          <p class="remote-sub">在可操作范围内点按开关与调控（空调 / 电视等）</p>
        </div>
        <button class="add-fab" @click="openAddDialog" aria-label="添加遥控">＋</button>
      </div>

      <p v-if="error" class="error-line">{{ error }}</p>
      <div v-if="loadError" class="mf-empty">{{ loadError }}</div>

      <template v-else>
        <div v-if="!remotes.length" class="mf-empty empty-remote">
          <span class="mf-empty__mark">📡</span>
          <span>还没有遥控，点右上角 ＋ 添加空调或电视</span>
          <button class="solid-btn empty-add" @click="openAddDialog">添加遥控</button>
        </div>

        <div v-else class="remote-grid">
          <button
            v-for="device in remotes"
            :key="device.entityId"
            class="remote-card"
            :class="{ offline: device.isOnline === false, on: isOn(device) }"
            @click="openDetail(device)"
          >
            <span class="remote-card-icon">{{ getDeviceIcon(device) }}</span>
            <span class="remote-card-name">{{ device.deviceName || device.entityId }}</span>
            <span class="remote-card-meta">{{ device.brand || brandFallback(device) }}</span>
            <span class="remote-card-state">{{ statusText(device) }}</span>
          </button>
        </div>
      </template>
    </template>

    <!-- 遥控面板 -->
    <div v-if="detailDevice" class="detail-overlay" @click.self="closeDetail">
      <div class="detail-sheet remote-sheet">
        <div class="detail-head">
          <button class="back-btn" @click="closeDetail">‹ 返回</button>
          <span class="detail-title">{{ detailDevice.deviceName }}</span>
          <button
            v-if="canRemove(detailDevice)"
            class="text-danger"
            @click="removeDevice(detailDevice)"
          >删除</button>
        </div>

        <div class="remote-hero">
          <span class="detail-icon">{{ getDeviceIcon(detailDevice) }}</span>
          <div>
            <div class="detail-name">{{ detailDevice.deviceName }}</div>
            <div class="detail-brand">{{ detailDevice.brand || brandFallback(detailDevice) }}</div>
          </div>
          <button
            v-if="isSwitchable(detailDevice)"
            class="power-btn"
            :class="{ on: isOn(detailDevice) }"
            :disabled="pending[detailDevice.entityId]"
            @click="toggle(detailDevice)"
          >{{ isOn(detailDevice) ? "关闭" : "开启" }}</button>
        </div>

        <p v-if="detailError" class="error-line">{{ detailError }}</p>
        <div v-if="pending[detailDevice.entityId]" class="detail-pending">指令发送中…</div>

        <div v-if="detailDevice.entityType === 'climate'" class="remote-pad">
          <div class="temp-readout">
            <span class="temp-now">{{ targetTemp(detailDevice) }}<small>°C</small></span>
          </div>
          <div class="temp-adjust">
            <button :disabled="pending[detailDevice.entityId]" @click="adjustTemp(detailDevice, -1)">−</button>
            <span>温度</span>
            <button :disabled="pending[detailDevice.entityId]" @click="adjustTemp(detailDevice, 1)">＋</button>
          </div>
          <div class="chip-block">
            <div class="section-label">模式</div>
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

        <div v-else-if="detailDevice.entityType === 'media_player'" class="remote-pad">
          <div class="volume-row">
            <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="toggleMute(detailDevice)">
              {{ isMuted(detailDevice) ? "静音" : "音量" }}
            </button>
            <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="adjustVolume(detailDevice, -10)">−</button>
            <span class="volume-num">{{ volumePct(detailDevice) }}%</span>
            <button class="round-btn" :disabled="pending[detailDevice.entityId]" @click="adjustVolume(detailDevice, 10)">＋</button>
          </div>
          <div class="play-row">
            <button :disabled="pending[detailDevice.entityId]" @click="mediaCmd(detailDevice, 'media_previous_track')">上一台</button>
            <button
              class="play-main"
              :disabled="pending[detailDevice.entityId]"
              @click="mediaCmd(detailDevice, detailDevice.state === 'paused' ? 'media_play' : 'media_pause')"
            >{{ detailDevice.state === 'paused' ? '播放' : '暂停' }}</button>
            <button :disabled="pending[detailDevice.entityId]" @click="mediaCmd(detailDevice, 'media_next_track')">下一台</button>
          </div>
          <div v-if="sources(detailDevice).length" class="chip-block">
            <div class="section-label">信号源</div>
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
        </div>

        <div v-else-if="detailDevice.entityType === 'light'" class="remote-pad">
          <div class="section-label">亮度 {{ brightnessPct(detailDevice) }}%</div>
          <div class="brightness-row">
            <button :disabled="pending[detailDevice.entityId]" @click="nudgeBrightness(detailDevice, -10)">暗</button>
            <button :disabled="pending[detailDevice.entityId]" @click="nudgeBrightness(detailDevice, 10)">亮</button>
          </div>
        </div>

        <p class="remote-hint">请在家里可操作范围内使用。遥控状态保存在本家庭账号下。</p>
      </div>
    </div>

    <!-- 添加遥控：选类型 → 选品牌 → 名称 -->
    <div v-if="showDeviceDialog" class="modal-overlay" @click.self="closeDeviceDialog">
      <div class="mf-card modal-card add-modal">
        <p class="modal-title">添加遥控</p>
        <p class="modal-desc">选择类型和品牌，加入家庭智能遥控。</p>
        <p v-if="dialogError" class="error-line">{{ dialogError }}</p>

        <div class="add-step-label">① 遥控器类型</div>
        <div class="type-grid">
          <button
            v-for="t in remoteTypes"
            :key="t.value"
            class="type-tile"
            :class="{ selected: addForm.entityType === t.value }"
            @click="pickType(t)"
          >
            <span class="type-icon">{{ t.icon }}</span>
            <span>{{ t.label }}</span>
          </button>
        </div>

        <div class="add-step-label">② 热门品牌</div>
        <div class="brand-list">
          <button
            v-for="b in brandsForType"
            :key="b"
            class="brand-row"
            :class="{ selected: addForm.brand === b }"
            @click="addForm.brand = b"
          >{{ b }}</button>
        </div>

        <label class="field">
          <span class="field-label">③ 名称</span>
          <input
            v-model="addForm.deviceName"
            class="field-input"
            :placeholder="defaultName"
            maxlength="64"
          />
        </label>

        <button class="solid-btn full-btn" :disabled="savingDevice" @click="submitAddForm">
          {{ savingDevice ? "添加中…" : "加入智能遥控" }}
        </button>
        <div class="modal-actions">
          <button class="ghost-btn" @click="closeDeviceDialog">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { request } from "@app/utils/request";
import {
  getLightBrightness, supportsColorControl,
  isDeviceOn, getTargetTemp, getCurrentTemp, getHvacModes, getFanModes,
  getHvacMode, getFanMode, getVolumePct, isMuted, getSources,
  getCurrentSource, isTv, modeLabel,
} from "./iot-logic.mjs";

const REMOTE_TYPES = [
  { value: "climate", label: "空调", icon: "❄️" },
  { value: "media_player", label: "电视", icon: "📺" },
  { value: "light", label: "灯", icon: "💡" },
  { value: "switch", label: "插座", icon: "🔌" },
];

const BRANDS = {
  climate: ["格力", "美的", "海尔", "海信", "TCL", "奥克斯", "小米", "长虹", "华凌"],
  media_player: ["海信", "小米", "TCL", "索尼", "创维", "华为", "三星", "LG"],
  light: ["小米", "飞利浦", "欧普", "涂鸦", "其他"],
  switch: ["小米", "公牛", "涂鸦", "其他"],
};

const BRAND_FALLBACK = {
  light: "智能灯", switch: "智能插座",
  climate: "空调遥控", media_player: "电视遥控",
};

export default {
  name: "IotPanel",

  data() {
    return {
      loading: true,
      error: "",
      loadError: "",
      devicesByArea: {},
      pending: {},
      pollTimer: null,
      detailDevice: null,
      detailError: "",
      showDeviceDialog: false,
      dialogError: "",
      savingDevice: false,
      remoteTypes: REMOTE_TYPES,
      addForm: {
        deviceName: "",
        entityType: "climate",
        brand: "美的",
        areaName: "家里",
      },
    };
  },

  computed: {
    remotes() {
      return Object.values(this.devicesByArea).flat()
        .filter(d => d.entityType !== "scene" && d.entityType !== "sensor");
    },
    brandsForType() {
      return BRANDS[this.addForm.entityType] || ["其他"];
    },
    defaultName() {
      const t = REMOTE_TYPES.find(x => x.value === this.addForm.entityType);
      const brand = this.addForm.brand || "";
      return `${brand}${t ? t.label : "设备"}遥控`;
    },
  },

  methods: {
    supportsColorControl,
    isOn: isDeviceOn, targetTemp: getTargetTemp, currentTemp: getCurrentTemp,
    hvacModes: getHvacModes, fanModes: getFanModes, hvacMode: getHvacMode,
    fanMode: getFanMode, volumePct: getVolumePct, isMuted, sources: getSources,
    currentSource: getCurrentSource, isTv, modeLabel,

    getDeviceIcon(device) {
      const icons = {
        light: "💡", switch: "🔌",
        climate: "❄️", media_player: this.isTv(device) ? "📺" : "🔊",
      };
      return icons[device.entityType] || "📱";
    },
    isSwitchable(device) {
      return ["light", "switch", "climate", "media_player"].includes(device.entityType);
    },
    brandFallback(device) {
      return BRAND_FALLBACK[device.entityType] || "遥控";
    },
    statusText(device) {
      if (device.isOnline === false) return "离线";
      if (device.entityType === "climate") {
        return this.modeLabel(this.hvacMode(device)) + " " + this.targetTemp(device) + "°C";
      }
      if (device.entityType === "media_player") {
        if (device.state === "paused") return "已暂停";
        return device.state === "on" ? "开启" : "关闭";
      }
      return device.state === "on" ? "开启" : "关闭";
    },
    brightnessPct(device) {
      return getLightBrightness(device);
    },

    /** API 仅允许删除 MANUAL / CLOUD；HUB 同步设备不可删。 */
    canRemove(device) {
      const src = device?.deviceSource;
      return !src || src === "MANUAL" || src === "CLOUD";
    },
    openDetail(device) {
      this.detailError = "";
      this.detailDevice = device;
    },
    closeDetail() {
      this.detailDevice = null;
      this.detailError = "";
    },

    load() {
      return this.init();
    },

    async init() {
      this.loading = true;
      this.loadError = "";
      try {
        await this.loadDevices();
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "加载失败";
      } finally {
        this.loading = false;
      }
    },

    async loadDevices() {
      this.loadError = "";
      try {
        const areas = await request("/iot/devices");
        const grouped = {};
        for (const group of areas || []) {
          grouped[group.areaName || "未分组"] = group.devices || [];
        }
        this.devicesByArea = grouped;
        if (this.detailDevice) {
          const all = Object.values(this.devicesByArea).flat();
          this.detailDevice = all.find(d => d.entityId === this.detailDevice.entityId) || null;
        }
      } catch (e) {
        if (e.status === 401) {
          this.$emit("session-ended");
          return;
        }
        this.loadError = e.message || "设备加载失败";
      }
    },

    async toggle(device) {
      await this.executeCommand(device, this.isOn(device) ? "turn_off" : "turn_on");
    },
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
    async nudgeBrightness(device, delta) {
      const pct = Math.min(100, Math.max(0, this.brightnessPct(device) + delta));
      await this.executeCommand(device, "turn_on", { brightness: Math.round((pct / 100) * 255) });
    },

    pickType(t) {
      this.addForm.entityType = t.value;
      const brands = BRANDS[t.value] || ["其他"];
      if (!brands.includes(this.addForm.brand)) this.addForm.brand = brands[0];
      if (!this.addForm.deviceName) this.addForm.deviceName = "";
    },

    openAddDialog() {
      this.dialogError = "";
      this.addForm = {
        deviceName: "",
        entityType: "climate",
        brand: "美的",
        areaName: "家里",
      };
      this.showDeviceDialog = true;
    },
    closeDeviceDialog() {
      this.showDeviceDialog = false;
    },

    async submitAddForm() {
      const name = (this.addForm.deviceName || "").trim() || this.defaultName;
      if (!this.addForm.brand) {
        this.dialogError = "请选择品牌";
        return;
      }
      this.savingDevice = true;
      this.dialogError = "";
      try {
        const attrs = {};
        if (this.addForm.entityType === "climate") {
          attrs.temperature = 26;
          attrs.hvac_mode = "cool";
          attrs.fan_mode = "auto";
        }
        if (this.addForm.entityType === "media_player") {
          attrs.source_list = ["HDMI1", "HDMI2", "AV"];
          attrs.volume_level = 0.3;
          attrs.device_class = "tv";
        }
        await request("/iot/devices", {
          method: "POST",
          data: {
            deviceName: name,
            entityType: this.addForm.entityType,
            brand: this.addForm.brand,
            areaName: this.addForm.areaName || "家里",
            state: "off",
            attributes: attrs,
          },
        });
        this.closeDeviceDialog();
        await this.loadDevices();
      } catch (e) {
        this.dialogError = e.message || "添加失败";
      } finally {
        this.savingDevice = false;
      }
    },

    async removeDevice(device) {
      if (!device?.entityId || !this.canRemove(device)) return;
      this.detailError = "";
      try {
        await request(`/iot/devices/${encodeURIComponent(device.entityId)}`, { method: "DELETE" });
        this.closeDetail();
        await this.loadDevices();
      } catch (e) {
        this.detailError = e.message || "删除失败";
      }
    },

    async executeCommand(device, commandType, parameters = null) {
      this.pending = { ...this.pending, [device.entityId]: true };
      this.error = "";
      this.detailError = "";
      try {
        await request(`/iot/devices/${device.entityId}/command`, {
          method: "POST", data: { commandType, parameters },
        });
        await this.loadDevices();
      } catch (e) {
        const msg = e.message || "操作失败";
        if (this.detailDevice?.entityId === device.entityId) this.detailError = msg;
        else this.error = msg;
      } finally {
        const next = { ...this.pending };
        delete next[device.entityId];
        this.pending = next;
      }
    },
  },

  mounted() {
    this.init();
    this.pollTimer = setInterval(() => {
      if (!this.loading) this.loadDevices();
    }, 8000);
  },

  beforeUnmount() {
    if (this.pollTimer) clearInterval(this.pollTimer);
  },
};
</script>

<style scoped>
.iot-panel { padding: 4px 0 28px; }
.remote-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px; margin-bottom: 16px;
}
.remote-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--c-ink); }
.remote-sub { margin: 6px 0 0; font-size: 12px; line-height: 1.5; color: var(--c-muted); }
.add-fab {
  width: 44px; height: 44px; border-radius: 22px; border: none; flex: none;
  font-size: 26px; line-height: 1; color: #fff; background: #4f7dc6;
  box-shadow: 0 8px 18px rgba(79, 125, 198, 0.35);
}
.error-line { margin: 8px 0; font-size: 12px; color: #c0392b; }
.mf-empty { text-align: center; padding: 36px 12px; font-size: 13px; color: var(--c-muted); }
.mf-empty__mark { display: block; font-size: 40px; margin-bottom: 10px; }
.empty-add { margin-top: 16px; height: 42px; padding: 0 22px; border-radius: 22px; border: none; color: #fff; background: #4f7dc6; }

.remote-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: 12px;
}
.remote-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
  padding: 16px 14px; text-align: left; border-radius: 18px;
  border: 1px solid var(--c-glass-line, rgba(0,0,0,.06));
  background: rgba(255, 255, 255, 0.72); color: var(--c-ink);
}
.remote-card.on { box-shadow: 0 6px 20px rgba(134, 187, 255, 0.28); }
.remote-card.offline { opacity: 0.55; }
.remote-card-icon { font-size: 28px; margin-bottom: 6px; }
.remote-card-name { font-size: 14px; font-weight: 650; width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.remote-card-meta, .remote-card-state { font-size: 11px; color: var(--c-muted); }

.detail-overlay {
  position: fixed; inset: 0; z-index: 90;
  display: flex; align-items: flex-end; justify-content: center;
  background: rgba(20, 30, 50, 0.4);
}
.detail-sheet {
  width: 100%; max-width: 480px; max-height: 88vh; overflow-y: auto;
  padding: 16px 20px 28px; background: var(--c-glass, #f7f9fc);
  border-radius: 22px 22px 0 0;
}
.detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.back-btn, .text-danger {
  padding: 6px 12px; border-radius: 16px; font-size: 13px;
  border: 1px solid var(--c-line); background: rgba(255,255,255,.7); color: var(--c-ink-2);
}
.text-danger { margin-left: auto; color: #c0392b; border-color: rgba(192,57,43,.25); }
.detail-title { font-size: 15px; font-weight: 650; }
.remote-hero {
  display: flex; align-items: center; gap: 12px; padding: 14px;
  border-radius: 18px; background: rgba(255,255,255,.7);
}
.detail-icon { font-size: 36px; }
.detail-name { font-size: 17px; font-weight: 650; }
.detail-brand { font-size: 12px; color: var(--c-muted); margin-top: 2px; }
.power-btn {
  margin-left: auto; padding: 10px 14px; border-radius: 14px; border: 1px solid #4f7dc6;
  background: transparent; color: #4f7dc6; font-size: 13px; font-weight: 700;
}
.power-btn.on { background: #4f7dc6; color: #fff; }
.detail-pending { margin-top: 8px; font-size: 11px; color: #4f7dc6; }
.remote-pad { margin-top: 16px; padding: 14px; border-radius: 18px; background: rgba(255,255,255,.55); }
.section-label { font-size: 12px; font-weight: 600; color: var(--c-ink-2); margin-bottom: 10px; }
.temp-readout { margin-bottom: 10px; }
.temp-now { font-size: 40px; font-weight: 650; }
.temp-now small { font-size: 16px; }
.temp-adjust, .brightness-row, .volume-row, .play-row {
  display: flex; align-items: center; justify-content: center; gap: 14px; margin: 12px 0;
}
.temp-adjust button, .brightness-row button, .round-btn, .play-row button {
  min-width: 44px; height: 44px; padding: 0 12px; border-radius: 22px;
  border: 1px solid var(--c-line); background: #fff; color: var(--c-ink); font-size: 15px;
}
.play-main { min-width: 72px !important; background: #4f7dc6 !important; border-color: #4f7dc6 !important; color: #fff !important; }
.chip-block { margin-top: 14px; }
.chip-row { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  padding: 8px 12px; border-radius: 16px; border: 1px solid var(--c-line);
  background: #fff; color: var(--c-ink-2); font-size: 12px;
}
.chip.selected { background: #4f7dc6; border-color: #4f7dc6; color: #fff; }
.remote-hint { margin-top: 16px; font-size: 11px; line-height: 1.6; color: var(--c-muted); text-align: center; }

.modal-overlay {
  position: fixed; inset: 0; z-index: 100;
  display: grid; place-items: center; padding: 16px;
  background: rgba(20, 30, 50, 0.36);
}
.modal-card { width: 100%; max-width: 380px; max-height: 88vh; overflow-y: auto; padding: 20px; }
.modal-title { margin: 0 0 4px; font-size: 17px; font-weight: 650; }
.modal-desc { margin: 0 0 14px; font-size: 12px; color: var(--c-muted); line-height: 1.6; }
.add-step-label { margin: 12px 0 8px; font-size: 12px; font-weight: 700; color: var(--c-ink); }
.type-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.type-tile {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 12px 4px; border-radius: 14px; border: 1px solid var(--c-glass-line);
  background: rgba(255,255,255,.6); font-size: 11px; color: var(--c-ink-2);
}
.type-tile.selected { border-color: #4f7dc6; background: rgba(134, 187, 255, 0.2); color: #4f7dc6; font-weight: 700; }
.type-icon { font-size: 22px; }
.brand-list { max-height: 200px; overflow-y: auto; margin-bottom: 8px; }
.brand-row {
  display: block; width: 100%; text-align: left; padding: 12px 14px; margin-bottom: 6px;
  border-radius: 12px; border: 1px solid var(--c-glass-line); background: rgba(255,255,255,.55);
  font-size: 14px; color: var(--c-ink);
}
.brand-row.selected { border-color: #4f7dc6; background: rgba(134, 187, 255, 0.18); font-weight: 650; }
.field { display: block; margin: 10px 0 14px; }
.field-label { display: block; margin-bottom: 6px; font-size: 12px; font-weight: 600; }
.field-input {
  width: 100%; height: 44px; padding: 0 14px; box-sizing: border-box;
  border-radius: 14px; border: 1px solid var(--c-glass-line); background: rgba(255,255,255,.7);
  font-size: 14px;
}
.full-btn { width: 100%; height: 44px; border: none; border-radius: 22px; color: #fff; background: #4f7dc6; font-size: 14px; font-weight: 650; }
.full-btn:disabled { opacity: 0.5; }
.modal-actions { display: flex; margin-top: 10px; }
.ghost-btn, .solid-btn {
  flex: 1; height: 42px; border-radius: 22px; border: 1px solid var(--c-line);
  background: rgba(255,255,255,.7); color: var(--c-ink-2); font-weight: 600;
}
.solid-btn { border: none; background: #4f7dc6; color: #fff; }
</style>
