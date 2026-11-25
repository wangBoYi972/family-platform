/** 默认本地可控设备：无硬件时也能验证「App → 云 → 网关 → 执行」全链路。 */

export function defaultDevices() {
  return [
    {
      entityId: "climate.living_ac",
      entityType: "climate",
      deviceName: "客厅空调",
      areaName: "客厅",
      state: "off",
      attributes: {
        temperature: 26,
        current_temperature: 28,
        hvac_mode: "cool",
        fan_mode: "auto",
        ir: true,
      },
      online: true,
    },
    {
      entityId: "media_player.living_tv",
      entityType: "media_player",
      deviceName: "客厅电视",
      areaName: "客厅",
      state: "off",
      attributes: {
        volume_level: 0.4,
        is_volume_muted: false,
        source: "HDMI 1",
        ir: true,
      },
      online: true,
    },
    {
      entityId: "media_player.living_speaker",
      entityType: "media_player",
      deviceName: "客厅音响",
      areaName: "客厅",
      state: "playing",
      attributes: {
        volume_level: 0.5,
        is_volume_muted: false,
        media_title: "本地音乐",
      },
      online: true,
    },
    {
      entityId: "light.living_main",
      entityType: "light",
      deviceName: "客厅主灯",
      areaName: "客厅",
      state: "on",
      attributes: { brightness: 200, supported_features: 1 },
      online: true,
    },
  ];
}

export function applyCommand(device, commandType, parameters = {}) {
  const next = {
    ...device,
    attributes: { ...(device.attributes || {}) },
  };
  const type = device.entityType;

  switch (commandType) {
    case "turn_on":
      if (type === "climate") {
        const mode = next.attributes.hvac_mode && next.attributes.hvac_mode !== "off"
          ? next.attributes.hvac_mode
          : "cool";
        next.state = mode;
        next.attributes.hvac_mode = mode;
      } else if (type === "media_player") {
        next.state = "playing";
      } else {
        next.state = "on";
        if (parameters.brightness != null) next.attributes.brightness = Number(parameters.brightness);
      }
      break;
    case "turn_off":
      next.state = "off";
      break;
    case "set_temperature": {
      const temp = Number(parameters.temperature);
      if (!Number.isFinite(temp) || temp < 16 || temp > 35) {
        throw new Error("温度需在 16–35°C");
      }
      next.attributes.temperature = temp;
      if (next.state === "off") {
        next.state = "cool";
        next.attributes.hvac_mode = "cool";
      }
      break;
    }
    case "set_hvac_mode": {
      const mode = String(parameters.hvac_mode || "");
      const allowed = new Set(["off", "cool", "heat", "dry", "fan_only", "auto"]);
      if (!allowed.has(mode)) throw new Error("不支持的空调模式");
      next.attributes.hvac_mode = mode;
      next.state = mode === "off" ? "off" : mode;
      break;
    }
    case "set_fan_mode": {
      const fan = String(parameters.fan_mode || "");
      if (!["auto", "low", "medium", "high"].includes(fan)) throw new Error("不支持的风速");
      next.attributes.fan_mode = fan;
      break;
    }
    case "volume_set": {
      const vol = Number(parameters.volume_level);
      if (!Number.isFinite(vol) || vol < 0 || vol > 1) throw new Error("音量需在 0–1");
      next.attributes.volume_level = vol;
      next.attributes.is_volume_muted = false;
      break;
    }
    case "volume_mute":
      next.attributes.is_volume_muted = Boolean(parameters.is_volume_muted);
      break;
    case "media_play_pause":
      next.state = next.state === "playing" ? "paused" : "playing";
      break;
    case "select_source":
      if (!parameters.source) throw new Error("缺少音源");
      next.attributes.source = String(parameters.source);
      if (["off", "idle", "standby"].includes(next.state)) next.state = "playing";
      break;
    default:
      throw new Error("网关不支持命令: " + commandType);
  }
  return next;
}
