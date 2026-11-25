(function () {
  "use strict";

  function esc(value) {
    return String(value).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  }
  function yuan(cents) {
    var sign = cents < 0 ? "-" : "";
    var abs = Math.abs(cents);
    return sign + (abs / 100).toFixed(2);
  }
  function q(sel, root) { return (root || document).querySelector(sel); }
  function qa(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ---------- 目录 ---------- */
  var toggle = q(".nav-toggle");
  var nav = q(".site-nav");
  function setOpen(open) {
    if (!nav || !toggle) return;
    nav.classList.toggle("open", open);
    toggle.setAttribute("aria-expanded", open ? "true" : "false");
  }
  if (toggle && nav) {
    toggle.addEventListener("click", function () { setOpen(!nav.classList.contains("open")); });
    nav.addEventListener("click", function (event) {
      if (event.target.closest("a")) setOpen(false);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") setOpen(false);
    });
    document.addEventListener("click", function (event) {
      if (!event.target.closest(".site-header")) setOpen(false);
    });
    var links = qa("a[href^='#']", nav);
    var sections = links.map(function (link) { return document.querySelector(link.getAttribute("href")); }).filter(Boolean);
    if ("IntersectionObserver" in window && sections.length) {
      var visible = new Set();
      var observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) visible.add(entry.target.id);
          else visible.delete(entry.target.id);
        });
        var current = sections.find(function (section) { return visible.has(section.id); });
        links.forEach(function (link) {
          var on = current && link.getAttribute("href") === "#" + current.id;
          if (on) link.setAttribute("aria-current", "true");
          else link.removeAttribute("aria-current");
        });
      }, { rootMargin: "-40% 0px -45% 0px", threshold: 0.01 });
      sections.forEach(function (section) { observer.observe(section); });
    }
  }

  function bindSwitch(buttons, onSelect) {
    buttons.forEach(function (button, index) {
      button.addEventListener("click", function () { onSelect(button); });
      if (button.getAttribute("role") !== "tab") return;
      button.addEventListener("keydown", function (event) {
        var key = event.key;
        var next = null;
        if (key === "ArrowRight" || key === "ArrowDown") next = buttons[(index + 1) % buttons.length];
        else if (key === "ArrowLeft" || key === "ArrowUp") next = buttons[(index - 1 + buttons.length) % buttons.length];
        else if (key === "Home") next = buttons[0];
        else if (key === "End") next = buttons[buttons.length - 1];
        if (!next || next === button) return;
        event.preventDefault();
        onSelect(next);
        next.focus();
      });
    });
  }

  /* ---------- 首屏预览 ---------- */
  var stage = q("[data-stage]");
  if (stage) {
    bindSwitch(qa("[data-view-btn]", stage), function (button) {
      var view = button.getAttribute("data-view-btn");
      qa("[data-view-btn]", stage).forEach(function (item) {
        var on = item === button;
        item.setAttribute("aria-selected", on ? "true" : "false");
        item.tabIndex = on ? 0 : -1;
      });
      qa("[data-view]", stage).forEach(function (panel) {
        panel.hidden = panel.getAttribute("data-view") !== view;
      });
    });
    qa("[data-sub]", stage).forEach(function (group) {
      bindSwitch(qa("[data-sub-btn]", group), function (button) {
        var name = button.getAttribute("data-sub-btn");
        qa("[data-sub-btn]", group).forEach(function (item) {
          item.setAttribute("aria-pressed", item === button ? "true" : "false");
        });
        qa("[data-sub-pane]", group).forEach(function (pane) {
          pane.hidden = pane.getAttribute("data-sub-pane") !== name;
        });
      });
    });
  }

  /* ---------- 场景切换 ---------- */
  var showcase = q("[data-showcase]");
  if (showcase) {
    bindSwitch(qa("[data-feature]", showcase), function (button) {
      var name = button.getAttribute("data-feature");
      qa("[data-feature]", showcase).forEach(function (item) {
        var on = item === button;
        item.setAttribute("aria-selected", on ? "true" : "false");
        item.tabIndex = on ? 0 : -1;
      });
      qa("[data-feature-panel]", showcase).forEach(function (panel) {
        panel.hidden = panel.getAttribute("data-feature-panel") !== name;
      });
    });
  }

  /* ---------- 家居演示 ---------- */
  var iotRoot = q("[data-iot]");
  if (iotRoot) {
    var devices = [
      { id: "light.living_room_main", name: "客厅主灯", area: "客厅", type: "light", source: "HUB", on: true, brightness: 80 },
      { id: "climate.living_room_ac", name: "客厅空调", area: "客厅", type: "climate", source: "HUB", on: true, temp: 26, mode: "cool", fan: "auto" },
      { id: "media_player.living_room_tv", name: "客厅电视", area: "客厅", type: "media_player", source: "HUB", on: false, volume: 30, muted: false, channel: "HDMI 1" },
      { id: "sensor.living_room_temp", name: "客厅温度", area: "客厅", type: "sensor", source: "HUB", value: "24.5°C" },
      { id: "switch.bedroom_humidifier", name: "加湿器", area: "主卧", type: "switch", source: "HUB", on: true }
    ];
    var selectedId = null;
    var iotLog = "演示中枢：命令只改本地快照。发现结果只有状态文案，不带地址和令牌。";
    var modes = ["cool", "heat", "dry", "fan_only", "auto", "off"];
    var modeLabel = { cool: "制冷", heat: "制热", dry: "除湿", fan_only: "送风", auto: "自动", off: "关闭" };
    var fans = ["auto", "low", "mid", "high"];
    function findDevice(id) { return devices.find(function (item) { return item.id === id; }); }
    function summary(item) {
      if (item.type === "sensor") return item.value;
      if (item.type === "climate") return (item.on ? modeLabel[item.mode] : "关闭") + " " + item.temp + "°C";
      if (item.type === "media_player") return (item.on ? "开" : "关") + " · " + item.channel + " · " + item.volume + "%";
      if (item.type === "light" && item.on) return "开 · 亮度 " + item.brightness + "%";
      return item.on ? "开" : "关";
    }
    function renderIot() {
      var html = '<p class="demo-flag">示例界面 · 演示数据 · 卡片列表</p>';
      if (!selectedId) {
        html += '<div class="device-grid">';
        devices.forEach(function (item) {
          html += '<button type="button" class="device-card' + (item.on ? " on" : "") + '" data-open="' + esc(item.id) + '">';
          html += '<span><span class="name">' + esc(item.name) + '</span><span class="sub">' + esc(item.area) + " · " + esc(item.source) + " · " + esc(summary(item)) + "</span></span>";
          html += "</button>";
        });
        html += '</div><div class="scene-row">';
        html += '<button type="button" class="mini-btn" data-add-manual>添加手动设备</button>';
        ["离家模式", "影院模式"].forEach(function (name) {
          html += '<button type="button" class="scene-btn" data-scene="' + esc(name) + '">' + esc(name) + "</button>";
        });
        html += "</div>";
      } else {
        var item = findDevice(selectedId);
        if (!item) selectedId = null;
        else {
          html += '<div class="check-row"><strong>设备明细</strong><button type="button" class="mini-btn" data-back>返回列表</button></div>';
          html += '<p class="demo-note">' + esc(item.name) + " · " + esc(item.type) + " · " + esc(item.source) + "</p>";
          if (item.type === "sensor") {
            html += '<p class="sensor">' + esc(item.value) + "</p>";
          } else if (item.type === "climate") {
            html += '<div class="check-row"><span>温度 ' + item.temp + '°C（16–35）</span><span>';
            html += '<button type="button" class="mini-btn" data-temp="-1">-</button> <button type="button" class="mini-btn" data-temp="1">+</button></span></div>';
            html += '<div class="pills">';
            modes.forEach(function (mode) {
              html += '<button type="button" class="chip" data-mode="' + mode + '" aria-pressed="' + (item.mode === mode ? "true" : "false") + '">' + modeLabel[mode] + "</button>";
            });
            html += "</div><div class=\"pills\">";
            fans.forEach(function (fan) {
              html += '<button type="button" class="chip" data-fan="' + fan + '" aria-pressed="' + (item.fan === fan ? "true" : "false") + '">风速 ' + fan + "</button>";
            });
            html += "</div>";
          } else if (item.type === "media_player") {
            html += '<div class="check-row"><span>' + (item.on ? "播放中" : "已关闭") + "</span>";
            html += '<button type="button" class="switch-pill" data-iot-toggle="' + esc(item.id) + '" aria-pressed="' + (item.on ? "true" : "false") + '" aria-label="开关电视"><span class="knob"></span></button></div>';
            html += '<div class="check-row"><span>音量 ' + item.volume + "%</span><span>";
            html += '<button type="button" class="mini-btn" data-vol="-10">-</button> <button type="button" class="mini-btn" data-vol="10">+</button></span></div>';
            html += '<div class="scene-row"><button type="button" class="mini-btn" data-mute>' + (item.muted ? "取消静音" : "静音") + '</button>';
            ["HDMI 1", "HDMI 2", "TV"].forEach(function (channel) {
              html += '<button type="button" class="chip" data-channel="' + esc(channel) + '" aria-pressed="' + (item.channel === channel ? "true" : "false") + '">' + esc(channel) + "</button>";
            });
            html += "</div>";
          } else {
            html += '<div class="check-row"><span>' + summary(item) + '</span><button type="button" class="switch-pill" data-iot-toggle="' + esc(item.id) + '" aria-pressed="' + (item.on ? "true" : "false") + '" aria-label="开关"><span class="knob"></span></button></div>';
          }
          html += '<div class="scene-row"><button type="button" class="mini-btn" data-test>测试响应</button>';
          if (item.source === "MANUAL") html += '<button type="button" class="mini-btn" data-remove>删除手动设备</button>';
          html += "</div>";
        }
      }
      html += '<p class="log-line" role="status">' + esc(iotLog) + "</p>";
      iotRoot.innerHTML = html;
    }
    function applyScene(name) {
      devices.forEach(function (item) {
        if (item.type === "sensor") return;
        item.on = false;
        if (item.type === "climate") item.mode = "off";
      });
      if (name === "影院模式") {
        var strip = findDevice("light.living_room_main");
        var tv = findDevice("media_player.living_room_tv");
        if (strip) { strip.on = true; strip.brightness = 18; }
        if (tv) { tv.on = true; tv.channel = "HDMI 1"; tv.volume = 30; }
      }
      iotLog = "已触发「" + name + "」。演示数据只改本地快照；真实中枢的状态变化会经 WebSocket 回写。";
    }
    iotRoot.addEventListener("click", function (event) {
      var open = event.target.closest("[data-open]");
      var back = event.target.closest("[data-back]");
      var toggleBtn = event.target.closest("[data-iot-toggle]");
      var sceneBtn = event.target.closest("[data-scene]");
      var tempBtn = event.target.closest("[data-temp]");
      var modeBtn = event.target.closest("[data-mode]");
      var fanBtn = event.target.closest("[data-fan]");
      var volBtn = event.target.closest("[data-vol]");
      var muteBtn = event.target.closest("[data-mute]");
      var channelBtn = event.target.closest("[data-channel]");
      var testBtn = event.target.closest("[data-test]");
      var addBtn = event.target.closest("[data-add-manual]");
      var removeBtn = event.target.closest("[data-remove]");
      if (open) selectedId = open.getAttribute("data-open");
      if (back) selectedId = null;
      if (toggleBtn) {
        var found = findDevice(toggleBtn.getAttribute("data-iot-toggle"));
        if (found) {
          found.on = !found.on;
          iotLog = found.type + "." + (found.on ? "turn_on" : "turn_off") + " · " + found.name;
        }
      }
      if (sceneBtn) applyScene(sceneBtn.getAttribute("data-scene"));
      if (tempBtn) {
        var ac = findDevice("climate.living_room_ac");
        var next = ac.temp + Number(tempBtn.getAttribute("data-temp"));
        if (next < 16 || next > 35) iotLog = "climate.set_temperature 被拒绝：温度必须在 16–35，接口返回 400。";
        else { ac.temp = next; ac.on = true; if (ac.mode === "off") ac.mode = "cool"; iotLog = "climate.set_temperature · " + next + "°C"; }
      }
      if (modeBtn) {
        var climate = findDevice("climate.living_room_ac");
        climate.mode = modeBtn.getAttribute("data-mode");
        climate.on = climate.mode !== "off";
        iotLog = "climate.set_hvac_mode · " + climate.mode;
      }
      if (fanBtn) {
        findDevice("climate.living_room_ac").fan = fanBtn.getAttribute("data-fan");
        iotLog = "climate.set_fan_mode · " + fanBtn.getAttribute("data-fan");
      }
      if (volBtn) {
        var tv = findDevice("media_player.living_room_tv");
        tv.volume = Math.max(0, Math.min(100, tv.volume + Number(volBtn.getAttribute("data-vol"))));
        iotLog = "media_player.volume_set · " + (tv.volume / 100).toFixed(1);
      }
      if (muteBtn) {
        var player = findDevice(selectedId);
        player.muted = !player.muted;
        iotLog = "media_player.volume_mute · " + (player.muted ? "静音" : "取消静音");
      }
      if (channelBtn) {
        findDevice(selectedId).channel = channelBtn.getAttribute("data-channel");
        iotLog = "media_player.select_source · " + channelBtn.getAttribute("data-channel");
      }
      if (testBtn) {
        var target = findDevice(selectedId);
        iotLog = "「" + target.name + "」在这个示例里不连接真实硬件。真实中枢设备会改读 Home Assistant 上的当前状态。";
      }
      if (addBtn) {
        var id = "manual.light." + devices.length;
        devices.push({ id: id, name: "自定义台灯", area: "自定义", type: "light", source: "MANUAL", on: false, brightness: 40 });
        selectedId = id;
        iotLog = "已添加 MANUAL 设备。只能改删手动设备，同步不会覆盖它。";
      }
      if (removeBtn) {
        devices = devices.filter(function (item) { return item.id !== selectedId; });
        selectedId = null;
        iotLog = "已删除手动设备。中枢同步来的设备不能从这里删。";
      }
      if (open || back || toggleBtn || sceneBtn || tempBtn || modeBtn || fanBtn || volBtn || muteBtn || channelBtn || testBtn || addBtn || removeBtn) renderIot();
    });
    renderIot();
  }

  /* ---------- 账本演示 ---------- */
  var ledgerRoot = q("[data-ledger]");
  if (ledgerRoot) {
    var members = ["林夏", "周宁"];
    var entries = [
      { month: "2026-11", type: "EXPENSE", category: "餐饮", cents: 8600, payer: "林夏", note: "周末晚饭", splits: [["林夏", 4300], ["周宁", 4300]] },
      { month: "2026-11", type: "EXPENSE", category: "日用", cents: 3200, payer: "周宁", note: "纸巾和洗衣液", splits: [["林夏", 1600], ["周宁", 1600]] },
      { month: "2026-11", type: "EXPENSE", category: "住房", cents: 150000, payer: "林夏", note: "本月房租", splits: [["林夏", 75000], ["周宁", 75000]] },
      { month: "2026-11", type: "EXPENSE", category: "交通", cents: 4800, payer: "周宁", note: "周末往返", splits: [["林夏", 2400], ["周宁", 2400]] },
      { month: "2026-11", type: "INCOME", category: "其他", cents: 20000, payer: "林夏", note: "家庭备用金转入", splits: [] },
      { month: "2026-10", type: "EXPENSE", category: "医疗", cents: 4500, payer: "周宁", note: "社区就诊", splits: [["林夏", 2250], ["周宁", 2250]] },
      { month: "2026-10", type: "EXPENSE", category: "教育", cents: 12800, payer: "林夏", note: "课外材料", splits: [["林夏", 6400], ["周宁", 6400]] },
      { month: "2026-10", type: "EXPENSE", category: "餐饮", cents: 6400, payer: "周宁", note: "月中聚餐", splits: [["林夏", 3200], ["周宁", 3200]] },
      { month: "2026-10", type: "EXPENSE", category: "其他", cents: 900, payer: "林夏", note: "打印", splits: [["林夏", 900]] }
    ];
    var categories = ["全部", "餐饮", "日用", "住房", "交通", "医疗", "教育", "其他"];
    var ledgerMonth = "2026-11";
    var ledgerCategory = "全部";
    function summaryOf(month) {
      var nets = {};
      members.forEach(function (name) { nets[name] = { income: 0, paid: 0, share: 0 }; });
      var income = 0;
      var expense = 0;
      entries.forEach(function (entry) {
        if (entry.month !== month) return;
        var payer = nets[entry.payer];
        if (entry.type === "INCOME") {
          income += entry.cents;
          payer.income += entry.cents;
          return;
        }
        expense += entry.cents;
        payer.paid += entry.cents;
        entry.splits.forEach(function (part) { nets[part[0]].share += part[1]; });
      });
      return { income: income, expense: expense, nets: nets };
    }
    function renderLedger() {
      var summary = summaryOf(ledgerMonth);
      var visible = entries.filter(function (entry) {
        return entry.month === ledgerMonth && (ledgerCategory === "全部" || entry.category === ledgerCategory);
      });
      var html = '<p class="demo-flag">示例界面 · 演示数据 · 金额为整数分</p>';
      html += '<div class="pills" role="group" aria-label="演示月份">';
      ["2026-11", "2026-10"].forEach(function (month) {
        html += '<button type="button" data-month="' + month + '" aria-pressed="' + (ledgerMonth === month ? "true" : "false") + '">' + month + "</button>";
      });
      html += '</div><div class="money"><div><span>收入</span><b class="in">¥' + yuan(summary.income) + '</b></div><div><span>支出</span><b class="out">¥' + yuan(summary.expense) + '</b></div></div>';
      members.forEach(function (name) {
        var net = summary.nets[name].income + summary.nets[name].paid - summary.nets[name].share;
        var label = net > 0 ? "该收" : net < 0 ? "该付" : "持平";
        html += '<div class="net-line"><span>' + esc(name) + '</span><strong class="' + (net > 0 ? "pos" : net < 0 ? "neg" : "") + '">' + label + " ¥" + yuan(Math.abs(net)) + "</strong></div>";
      });
      html += '<p class="demo-note">以上净额按整月计算，不随下面的分类筛选变化。正数是该收，负数是该付。</p>';
      html += '<div class="pills" role="group" aria-label="分类">';
      categories.forEach(function (category) {
        html += '<button type="button" class="chip" data-category="' + esc(category) + '" aria-pressed="' + (ledgerCategory === category ? "true" : "false") + '">' + esc(category) + "</button>";
      });
      html += '</div><div class="entry-list" style="margin-top:8px">';
      if (!visible.length) html += '<p class="demo-note">这个月的演示账里没有「' + esc(ledgerCategory) + '」。</p>';
      visible.forEach(function (entry) {
        var splitText = entry.type === "INCOME" ? "收入不分摊" : entry.splits.map(function (part) { return part[0] + " ¥" + yuan(part[1]); }).join(" · ");
        html += '<article class="entry"><strong>' + esc(entry.category) + " · " + (entry.type === "INCOME" ? "收入" : "支出") + '</strong><span class="amt ' + (entry.type === "INCOME" ? "in" : "out") + '">' + (entry.type === "INCOME" ? "+" : "-") + "¥" + yuan(entry.cents) + '</span><span class="meta">' + (entry.type === "INCOME" ? "收款" : "垫付") + "：" + esc(entry.payer) + " · " + esc(entry.note) + '</span><span class="meta">' + esc(splitText) + "</span></article>";
      });
      html += "</div>";
      ledgerRoot.innerHTML = html;
    }
    ledgerRoot.addEventListener("click", function (event) {
      var monthBtn = event.target.closest("[data-month]");
      var categoryBtn = event.target.closest("[data-category]");
      if (monthBtn) ledgerMonth = monthBtn.getAttribute("data-month");
      if (categoryBtn) ledgerCategory = categoryBtn.getAttribute("data-category");
      if (monthBtn || categoryBtn) renderLedger();
    });
    renderLedger();
  }

  /* ---------- 档案演示 ---------- */
  var archiveRoot = q("[data-archive]");
  if (archiveRoot) {
    var archives = [
      { title: "年度体检摘要", visibility: "FAMILY", body: "血压与血糖在参考范围内。演示正文，不是真实病历。" },
      { title: "用药计划 · 维生素", visibility: "PRIVATE", body: "每日早餐后 1 次。药名与剂量均为演示。" }
    ];
    var doseTaken = false;
    var followSeen = false;
    function renderArchive() {
      var html = '<p class="demo-flag">示例界面 · 演示数据 · 正文在库内加密</p>';
      archives.forEach(function (item, index) {
        var mine = item.visibility === "PRIVATE";
        html += '<article class="archive-card"><h4>' + esc(item.title) + "</h4>";
        html += '<p class="lock">' + (mine ? "仅自己可见 · 其他成员与管理员都看不到" : "家庭成员可见") + "</p>";
        html += "<p>" + esc(item.body) + "</p>";
        html += '<div class="check-row"><span>可见范围</span><button type="button" class="mini-btn" data-vis="' + index + '">' + (mine ? "改为家庭可见" : "改为仅自己可见") + "</button></div></article>";
      });
      html += '<article class="archive-card"><h4>今日健康提醒</h4><p>复诊：11 月 28 日 · 社区医院内科（演示）</p>';
      html += '<div class="check-row"><span>' + (doseTaken ? "维生素已标记服用" : "维生素尚未服用") + '</span><button type="button" class="mini-btn" data-dose>' + (doseTaken ? "撤销" : "标记已服用") + "</button></div>";
      html += '<div class="check-row"><span>' + (followSeen ? "今日复诊提醒已读" : "当天会提醒一次复诊") + '</span><button type="button" class="mini-btn" data-follow>' + (followSeen ? "重新打开" : "知道了") + "</button></div></article>";
      html += '<p class="banner" role="status">微信订阅消息模板尚未接入。家庭端和移动端只在当天弹一次本地提醒，不会因此发出订阅消息。</p>';
      archiveRoot.innerHTML = html;
    }
    archiveRoot.addEventListener("click", function (event) {
      var vis = event.target.closest("[data-vis]");
      if (vis) {
        var item = archives[Number(vis.getAttribute("data-vis"))];
        item.visibility = item.visibility === "PRIVATE" ? "FAMILY" : "PRIVATE";
      }
      if (event.target.closest("[data-dose]")) doseTaken = !doseTaken;
      if (event.target.closest("[data-follow]")) followSeen = !followSeen;
      if (vis || event.target.closest("[data-dose]") || event.target.closest("[data-follow]")) renderArchive();
    });
    renderArchive();
  }

  /* ---------- 协同内容 ---------- */
  var contentRoot = q("[data-content]");
  if (contentRoot) {
    var contentTab = "任务";
    var tasks = [
      { title: "周末买米", who: "林夏", state: "待开始" },
      { title: "整理阳台", who: "周宁", state: "进行中" }
    ];
    function renderContent() {
      var html = '<p class="demo-flag">示例界面 · 演示数据</p><div class="pills" role="tablist" aria-label="内容类型">';
      ["任务", "公告", "日历", "动态"].forEach(function (name) {
        html += '<button type="button" data-content-tab="' + name + '" aria-pressed="' + (contentTab === name ? "true" : "false") + '">' + name + "</button>";
      });
      html += "</div>";
      if (contentTab === "任务") {
        tasks.forEach(function (task, index) {
          html += '<article class="task-card"><strong>' + esc(task.title) + '</strong><div class="check-row"><span>' + esc(task.who) + " · " + esc(task.state) + '</span><button type="button" class="mini-btn" data-task="' + index + '">' + (task.state === "已完成" ? "重开" : "完成") + "</button></div></article>";
        });
      } else if (contentTab === "公告") {
        html += '<article class="task-card"><strong>本周家庭会议</strong><p class="meta">周日晚上，只给自家人看。公告按家庭存放。</p></article>';
      } else if (contentTab === "日历") {
        html += '<article class="task-card"><strong>11 月 28 日 · 家庭日</strong><p class="meta">日历与纪念日在同一服务里。当天纪念日登录后会弹一次，同一天不重复。</p></article>';
      } else {
        html += '<article class="task-card"><strong>阳台的晚霞</strong><p class="meta">动态与相册、时光轴都属于家庭内容。时光轴可以导出单文件 HTML 纪念册。</p></article>';
      }
      contentRoot.innerHTML = html;
    }
    contentRoot.addEventListener("click", function (event) {
      var tab = event.target.closest("[data-content-tab]");
      var taskBtn = event.target.closest("[data-task]");
      if (tab) contentTab = tab.getAttribute("data-content-tab");
      if (taskBtn) {
        var task = tasks[Number(taskBtn.getAttribute("data-task"))];
        task.state = task.state === "已完成" ? "待开始" : "已完成";
      }
      if (tab || taskBtn) renderContent();
    });
    renderContent();
  }

  /* ---------- 消息与通话 ---------- */
  var imRoot = q("[data-im]");
  if (imRoot) {
    var messages = [
      { who: "周宁", mine: false, text: "晚饭想吃汤面。" },
      { who: "我", mine: true, text: "好，我来煮。", recalled: false }
    ];
    var callState = "idle";
    var recording = false;
    function renderIm() {
      var html = '<p class="demo-flag">示例界面 · 演示数据 · 不会连接服务器</p>';
      if (callState !== "idle") {
        html += '<div class="call-bar" role="status"><span>' + (callState === "ring" ? "周宁 来电 · 信令在 family-media-svc" : "通话中 · 音视频走 WebRTC") + "</span>";
        html += '<span>' + (recording ? "● 录音中（演示）" : "未录音") + "</span></div>";
      }
      html += '<div class="chat">';
      messages.forEach(function (message) {
        html += '<div class="bubble' + (message.mine ? " mine" : "") + '"><span class="who">' + esc(message.who) + "</span>" + esc(message.recalled ? "此消息已撤回" : message.text) + "</div>";
      });
      html += '</div><form class="composer" data-composer><input name="text" maxlength="80" aria-label="演示消息" placeholder="写一句演示消息" /><button class="button" type="submit">发送</button></form>';
      html += '<div class="scene-row">';
      html += '<button type="button" class="mini-btn" data-call>' + (callState === "idle" ? "模拟来电" : callState === "ring" ? "接听" : "挂断") + "</button>";
      html += '<button type="button" class="mini-btn" data-record ' + (callState === "live" ? "" : "disabled") + ">" + (recording ? "停止纪要" : "通话纪要") + "</button>";
      html += '<button type="button" class="mini-btn" data-recall>撤回我的上一条</button></div>';
      html += '<p class="demo-note">私聊帧只投递给会话双方。撤回窗口是发出后 2 分钟，以服务端时间为准。录音在设备上混音，不经过服务器转发媒体；没勾选全员知情同意的提交会返回 400。</p>';
      imRoot.innerHTML = html;
    }
    imRoot.addEventListener("submit", function (event) {
      var form = event.target.closest("[data-composer]");
      if (!form) return;
      event.preventDefault();
      var input = form.elements.text;
      var text = input.value.trim();
      if (!text) return;
      messages.push({ who: "我", mine: true, text: text, recalled: false });
      renderIm();
    });
    imRoot.addEventListener("click", function (event) {
      if (event.target.closest("[data-call]")) {
        callState = callState === "idle" ? "ring" : callState === "ring" ? "live" : "idle";
        if (callState !== "live") recording = false;
        renderIm();
      } else if (event.target.closest("[data-record]")) {
        if (callState !== "live") return;
        recording = !recording;
        renderIm();
      } else if (event.target.closest("[data-recall]")) {
        for (var i = messages.length - 1; i >= 0; i -= 1) {
          if (messages[i].mine && !messages[i].recalled) {
            messages[i].recalled = true;
            break;
          }
        }
        renderIm();
      }
    });
    renderIm();
  }

  /* ---------- 小助手 ---------- */
  var aiRoot = q("[data-ai]");
  if (aiRoot) {
    var aiOn = false;
    function renderAi() {
      var html = '<p class="demo-flag">示例界面 · 未配置时助手关闭</p>';
      html += '<div class="pills" role="group" aria-label="助手配置"><button type="button" data-ai-mode="off" aria-pressed="' + (!aiOn ? "true" : "false") + '">未配置</button><button type="button" data-ai-mode="on" aria-pressed="' + (aiOn ? "true" : "false") + '">已配置演示</button></div>';
      if (!aiOn) {
        html += '<div class="off-state" role="status"><strong>AI 助手未配置</strong><p>FAMILY_AI_ENABLED 缺省为 false，且没有密钥时，/api/ai/chat 返回 503。历史接口仍可读。</p></div>';
      } else {
        html += '<div class="ai-stream"><span class="tool">正在查询任务…</span><p>这周末林夏有一件「买米」，周宁有一件「整理阳台」。演示回答只覆盖当前用户原本就能看到的数据，工具调用会转发这个用户自己的令牌。</p></div>';
      }
      html += '<p class="demo-note">小助手历史按用户和家庭保存在 family_ai，不写入即时消息库。最近 40 条可以换设备接着看。</p>';
      aiRoot.innerHTML = html;
    }
    aiRoot.addEventListener("click", function (event) {
      var mode = event.target.closest("[data-ai-mode]");
      if (!mode) return;
      aiOn = mode.getAttribute("data-ai-mode") === "on";
      renderAi();
    });
    renderAi();
  }

  /* ---------- 四端 ---------- */
  var clients = q("[data-clients]");
  if (clients) {
    bindSwitch(qa("[data-client]", clients), function (button) {
      var name = button.getAttribute("data-client");
      qa("[data-client]", clients).forEach(function (item) {
        var on = item === button;
        item.setAttribute("aria-selected", on ? "true" : "false");
        item.tabIndex = on ? 0 : -1;
      });
      qa("[data-client-panel]", clients).forEach(function (panel) {
        panel.hidden = panel.getAttribute("data-client-panel") !== name;
      });
    });
  }

  /* ---------- 架构 ---------- */
  var catalog = {
    clients: {
      title: "四类客户端",
      id: "apps/",
      body: "管理端、移动端、家庭微信小程序、档案微信小程序共用业务接口。能碰到什么，由已校验会话里的家庭、角色和 client 决定。",
      points: ["管理端只接受 ADMIN 且 client=admin 的会话。", "家庭端与移动端登录 client=family。", "档案端登录 client=archive，服务端再收窄路径。"],
      chips: [["Maven 以外", "na"], ["界面在 apps/", "ok"]]
    },
    gateway: {
      title: "统一入口",
      id: "family-gateway · :8080",
      body: "API 入口、路由、跨域和 JWT 前置校验。网关不存放业务表。WebSocket 用 lb:ws:// 转发 /ws/im 与 /ws/call。",
      points: ["匿名放行登录、密码登录和注册。", "注册仍必须持有管理员发放的邀请码。", "本地 CORS 放行 5173–5176。"],
      chips: [["Maven", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    user: {
      title: "成员与登录",
      id: "family-user-svc · :8081",
      body: "白名单、邀请码注册、会话、注销、家庭关系与角色。密码只存 BCrypt。每个家庭最多十位启用成员。",
      points: ["客户端只接受 admin、family、archive。", "家庭编号来自邀请码，注册人不能自选。", "微信订阅消息的凭证、配额和发送都收在这个服务；未配置则关闭。"],
      chips: [["Maven", "ok"], ["路由 /api/auth /api/users", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    content: {
      title: "家庭内容",
      id: "family-content-svc · :8082",
      body: "相册、动态、日历、纪念日、任务和公告。列表支持关键字、状态和分页。家庭条件与关键字用 and 分组，避免串户。",
      points: ["任务状态：待开始、进行中、已完成、逾期。", "文件删除事件会摘掉内容里的文件引用。", "管理端任务指标来自 /api/tasks/stats，不拿当前页相加。"],
      chips: [["Maven", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    file: {
      title: "文件",
      id: "family-file-svc · :8087",
      body: "私有文件上传、下载授权和元数据。有本地实现，也有可插拔的 S3 兼容适配器。编排里目前挂的是本地卷。",
      points: ["下载需要鉴权，客户端先换本地临时地址再打开。", "对象存储的真实验收仍在上线清单里。"],
      chips: [["Maven", "ok"], ["路由 /api/files", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"], ["对象存储验收", "gap"]]
    },
    archive: {
      title: "档案与健康",
      id: "family-archive-svc · :8088",
      body: "家庭档案、用药计划和复诊。正文加密保存，可见范围是 PRIVATE 或 FAMILY。管理员可看近 30 天访问汇总。",
      points: ["设为仅本人时，其他成员和管理员都看不到。", "家庭端与移动端当天提醒一次未服药和当天复诊。", "用药与复诊的微信订阅模板还没接上，扫描不会发订阅消息。"],
      chips: [["Maven", "ok"], ["路由 /api/archives", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    im: {
      title: "即时消息",
      id: "family-im-svc · :8089",
      body: "会话、文本 / 图片 / 语音 / 文件、已读和 WebSocket。新消息在事务提交后推送；断线时退回轮询。",
      points: ["私聊按双方投递，不进全家广播。", "两分钟内可撤回，界面留「此消息已撤回」。", "领域事件经 Outbox 和 Redis 扇出成 refresh。"],
      chips: [["Maven", "ok"], ["路由 /api/im /ws/im", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    media: {
      title: "实时通话",
      id: "family-media-svc · :8090",
      body: "通话状态、WebSocket 信令、TURN 时效凭据和通话记录。默认仍是 WebRTC mesh，最多 8 人。LiveKit SFU 的入房凭证已经能签，默认关闭，打开后上限 20 人，mesh 可以整段退回。",
      points: ["JoinToken 只签给这通通话里仍在邀请或已加入的人，identity 取会话里的用户，不信客户端自报身份。", "跨家庭的通话号按不存在处理。语音通话的凭证只允许麦克风。", "代码已在仓库里。公网部署和多人压测还没有做成已完成的验收。"],
      chips: [["Maven", "ok"], ["路由 /api/media /ws/call", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"], ["SFU 默认关闭", "gap"]]
    },
    ai: {
      title: "家庭小助手",
      id: "family-ai-svc · :8091",
      body: "用当前用户自己的令牌查询任务、日程、纪念日，并搜索动态、相册、公告、档案和文件。未配置模型时关闭。",
      points: ["响应是 SSE。工具轮数默认上限 3。", "历史按用户和家庭保留最近 40 条，不进 IM。", "管理端有开关页；家庭端和移动端在聊天里置顶入口。"],
      chips: [["Maven", "ok"], ["路由 /api/ai", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"], ["缺省关闭", "gap"]]
    },
    iot: {
      title: "家居设备",
      id: "family-iot-svc · :8092",
      body: "每家一条 Home Assistant，或内置演示中枢。私网和本机默认可绑定；公网主机必须出现在 FAMILY_IOT_ALLOWED_HOSTS 里，云元数据地址即使写进白名单也拒绝。接口不回传令牌和中枢地址。",
      points: ["命令白名单含灯、开关、场景，以及空调的温度、模式、风速，电视音响的音量、静音、频道和播放。空调温度只接受 16–35，越界返回 400。", "启动和绑定真实中枢后会连 WebSocket，状态变化写回快照；演示中枢跳过。这条连接仍要求地址通过私网校验。", "可增删改手动设备，只限本家庭的 MANUAL，同步不会覆盖。当前解绑会清掉该家庭全部设备快照。发现接口只返回状态文案。"],
      chips: [["Maven", "ok"], ["路由 /api/iot", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    ledger: {
      title: "家庭账本",
      id: "family-ledger-svc · :8093",
      body: "支出和收入按整数分记账。分类固定七种。支出要写垫付人和分摊，合计必须等于这笔账。收入记在收款人名下、不分摊。",
      points: ["本月收支和每人净额按整月在服务端计算。", "改删只限记账人或管理员。", "家庭编号只取已校验会话。"],
      chips: [["Maven", "ok"], ["路由 /api/ledger", "ok"], ["Dockerfile", "ok"], ["compose", "ok"], ["Prometheus", "ok"]]
    },
    common: {
      title: "family-common",
      id: "公共模块",
      body: "统一响应、分页归一化、全局异常、业务指标和请求追踪过滤器。不单独启动，也不进网关路由。",
      points: ["PageQuery 把 page/size 收口，上限 500。"],
      chips: [["Maven", "ok"], ["独立进程", "na"], ["Dockerfile", "na"]]
    },
    security: {
      title: "family-security",
      id: "公共模块",
      body: "校验 JWT，得到 userId、householdId、role、client。档案客户端在这里被限制路径。也负责 OpenAPI 与 Feign 透传 X-Request-Id。",
      points: ["issuer 固定为 family-home。", "角色只有 ADMIN 和 MEMBER。"],
      chips: [["Maven", "ok"], ["独立进程", "na"]]
    },
    messaging: {
      title: "family-messaging",
      id: "公共模块",
      body: "Outbox、RabbitMQ、Redis 票据、在线连接和幂等锁。已发布事件默认保留 7 天。",
      points: ["实时帧跨实例走 Redis family:ws:event。"],
      chips: [["Maven", "ok"], ["独立进程", "na"]]
    },
    api: {
      title: "family-api",
      id: "公共模块",
      body: "服务之间的 Feign 契约，目前覆盖用户与文件，以及成员缓存。调用失败有回退，避免把下游异常直接变成脏数据。",
      points: ["AI 服务另有自己的下游客户端，仍转发用户令牌。"],
      chips: [["Maven", "ok"], ["独立进程", "na"]]
    },
    mysql: {
      title: "MySQL",
      id: "infra · 3306",
      body: "生产配置面向 MySQL 8.4，每个服务自己的库。本地开发用各自的 H2，不在这个页面上写账号口令。",
      points: ["与 Nacos 的实例联调仍是上线验收项。"],
      chips: [["compose", "ok"], ["联调验收", "gap"]]
    },
    nacos: {
      title: "Nacos",
      id: "infra · 8848",
      body: "服务注册与发现。网关用服务名做 lb:// 路由。",
      points: ["鉴权令牌由环境变量注入，不写进页面。"],
      chips: [["compose", "ok"], ["联调验收", "gap"]]
    },
    redis: {
      title: "Redis",
      id: "infra · 6379",
      body: "限流、在线连接、WebSocket 跨实例扇出和一次性票据。",
      points: ["口令走环境变量。"],
      chips: [["compose", "ok"]]
    },
    rabbit: {
      title: "RabbitMQ",
      id: "infra · 5672",
      body: "领域事件总线。业务服务写 Outbox，再投递；im-svc 消费后通知在线客户端。",
      points: ["竞争消费下，刷新必须经 Redis 扇出到所有实例。"],
      chips: [["compose", "ok"]]
    },
    object: {
      title: "对象存储",
      id: "文件服务适配器",
      body: "S3 兼容适配器已经在文件服务里，编排当前使用本地卷。真实对象存储的验收还没写成完成项。",
      points: ["页面不提供桶名、密钥或地址。"],
      chips: [["代码适配器", "ok"], ["真实验收", "gap"]]
    },
    coturn: {
      title: "coturn",
      id: "infra · 3478",
      body: "TURN 中继。时效凭据用 HMAC-SHA1。编排使用 host 网络，监听 3478，中继端口 49160–49200。",
      points: ["公网穿透和压测仍是上线验收项。"],
      chips: [["compose", "ok"], ["公网压测", "gap"]]
    },
    hass: {
      title: "Home Assistant / 演示中枢",
      id: "家居上游",
      body: "真实中枢由家庭自己部署，地址必须是本机或私网。没有中枢时可以绑定 demo://local，设备状态留在本地快照。",
      points: ["演示中枢不发起出站请求。"],
      chips: [["进程内演示", "ok"], ["真实中枢回写", "gap"]]
    },
    model: {
      title: "可选模型服务",
      id: "family-ai-svc 上游",
      body: "对话和通话转写都依赖外部模型。未配置时助手与转写返回 503，其余家庭功能照常。",
      points: ["密钥只走环境变量或管理端保存，不进仓库，也不进这个页面。"],
      chips: [["缺省关闭", "gap"]]
    }
  };

  var archDetail = q("[data-arch-detail]");
  function selectNode(id) {
    var item = catalog[id];
    if (!item || !archDetail) return;
    qa(".node").forEach(function (node) {
      var on = node.getAttribute("data-node") === id;
      node.classList.toggle("is-on", on);
      node.setAttribute("aria-pressed", on ? "true" : "false");
    });
    var chips = item.chips.map(function (chip) {
      return '<span class="' + esc(chip[1]) + '">' + esc(chip[0]) + "</span>";
    }).join("");
    var points = item.points.map(function (point) { return "<li>" + esc(point) + "</li>"; }).join("");
    archDetail.innerHTML = '<p class="demo-flag">来自当前仓库</p><h3>' + esc(item.title) + '</h3><p class="id">' + esc(item.id) + "</p><p>" + esc(item.body) + "</p><ul class=\"plain-list\">" + points + '</ul><div class="status-pills">' + chips + "</div>";
  }
  qa(".node").forEach(function (node) {
    node.setAttribute("role", "button");
    node.setAttribute("tabindex", "0");
    node.setAttribute("aria-pressed", "false");
    function activate() { selectNode(node.getAttribute("data-node")); }
    node.addEventListener("click", activate);
    node.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        activate();
      }
    });
  });
  if (archDetail) selectNode("gateway");

  qa("[data-jump]").forEach(function (button) {
    button.addEventListener("click", function () {
      var id = button.getAttribute("data-jump");
      selectNode(id);
      var target = document.getElementById("architecture");
      if (target) target.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    });
  });
})();
