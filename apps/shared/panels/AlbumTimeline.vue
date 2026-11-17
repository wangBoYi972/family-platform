<script setup>
import { computed, onMounted, ref } from "vue";
import { request, loadImageUrl } from "@app/utils/request";
import { buildTimelineHtml, toDataUrl, exportTimelineHtml } from "@shared/timeline-export.mjs";

const emit = defineEmits(["session-ended", "go"]);
const events = ref([]);
const loading = ref(false), error = ref(""), month = ref("全部");
const progress = ref({ done: 0, total: 0 });

/** 并发跑任务，避免一次把几十个图片请求全打出去。 */
async function mapLimit(items, limit, worker) {
  const results = [];
  let cursor = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await worker(items[index], index);
    }
  });
  await Promise.all(runners);
  return results;
}

function fileIdsOf(row) { return String(row.fileIds || "").split(",").filter(Boolean); }
function dateOf(row) {
  const raw = row.eventDate || String(row.createdAt || "").slice(0, 10);
  return raw ? String(raw).slice(0, 10) : "";
}

async function load() {
  loading.value = true;
  error.value = "";
  progress.value = { done: 0, total: 0 };
  try {
    const [albums, dynamics] = await Promise.all([request("/albums"), request("/dynamics")]);
    // 相册与动态都带 fileIds，一起进时间轴；没有照片的条目不占位。
    const merged = [
      ...albums.map(item => ({ ...item, source: "相册", sourceIcon: "◫" })),
      ...dynamics.map(item => ({ ...item, source: "动态", sourceIcon: "▧" }))
    ]
      .filter(item => fileIdsOf(item).length)
      .map(item => ({ ...item, date: dateOf(item), images: [] }))
      .filter(item => item.date)
      .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
      .slice(0, 24);

    const wanted = [];
    for (const item of merged) for (const id of fileIdsOf(item).slice(0, 6)) wanted.push({ item, id });
    progress.value = { done: 0, total: wanted.length };
    await mapLimit(wanted, 6, async entry => {
      try {
        const url = await loadImageUrl(entry.id);
        if (url) entry.item.images.push(url);
      } catch {
        // 单张失败不阻塞整条时间轴
      } finally {
        progress.value.done++;
      }
    });
    events.value = merged.filter(item => item.images.length);
  } catch (e) {
    error.value = e.message || "时光轴加载失败";
    if (e.status === 401) emit("session-ended");
  } finally {
    loading.value = false;
  }
}

/** 按年月分组，组内部按日期倒序。 */
const groups = computed(() => {
  const map = new Map();
  for (const item of events.value) {
    const key = item.date.slice(0, 7);
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(item);
  }
  return [...map.entries()]
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .map(([key, items]) => ({ key, label: formatMonth(key), items }));
});

const months = computed(() => ["全部", ...groups.value.map(item => item.key)]);
const visibleGroups = computed(() =>
  month.value === "全部" ? groups.value : groups.value.filter(item => item.key === month.value)
);
const photoCount = computed(() => events.value.reduce((sum, item) => sum + item.images.length, 0));

function formatMonth(key) {
  const [year, m] = key.split("-");
  return `${year} 年 ${Number(m)} 月`;
}
function formatDay(date) {
  const [, month, day] = date.split("-");
  return `${Number(month)}/${Number(day)}`;
}
function preview(item, index) { uni.previewImage({ urls: item.images, current: item.images[index] }); }

// 导出纪念册：把当前已加载的时刻（界面看到的这份）拼成单文件 HTML，图片 base64 内嵌。
// 不重新拉全量数据——导出的就是眼前这份时光轴，小而确定；全量导出等以后有真实需求再做。
const exporting = ref({ busy: false, done: 0, total: 0 });
async function exportAlbum() {
  if (exporting.value.busy || !events.value.length) return;
  exporting.value = { busy: true, done: 0, total: 0 };
  try {
    const exportGroups = groups.value.map(group => ({
      label: group.label,
      items: group.items.map(item => ({
        dateLabel: formatDay(item.date),
        source: item.sourceIcon + " " + item.source,
        title: item.title || "未命名时刻",
        note: item.body || "",
        images: item.images
      }))
    }));
    exporting.value.total = exportGroups.reduce(
      (sum, g) => sum + g.items.reduce((n, item) => n + item.images.length, 0), 0);
    for (const group of exportGroups) for (const item of group.items) {
      item.images = await mapLimit(item.images, 3, async url => {
        try { return await toDataUrl(url); }
        catch { return null; }   // 单张失败占位，不阻塞整本
        finally { exporting.value.done++; }
      });
    }
    const stamp = new Date().toISOString().slice(0, 10).replaceAll("-", "");
    const html = buildTimelineHtml({
      title: "家庭时光轴",
      exportedAt: new Date().toISOString().slice(0, 10),
      groups: exportGroups
    });
    uni.showToast({ title: await exportTimelineHtml(html, "家庭时光轴-" + stamp + ".html"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: e.message || "导出失败", icon: "none" });
  } finally {
    exporting.value = { busy: false, done: 0, total: 0 };
  }
}

// 不分页的面板：sync 与 load 等价，统一方法名好让宿主一视同仁。
defineExpose({ load, sync: load });
onMounted(load);
</script>

<template>
  <view class="timeline">
    <view class="metrics">
      <view class="metric"><text class="figure">{{ events.length }}</text><text class="caption">个时刻</text></view>
      <view class="metric"><text class="figure">{{ photoCount }}</text><text class="caption">张照片</text></view>
      <view class="metric"><text class="figure">{{ groups.length }}</text><text class="caption">个月份</text></view>
    </view>

    <view v-if="months.length > 2" class="months">
      <text v-for="item in months" :key="item" class="month-chip" :class="{ active: month === item }" @click="month = item">
        {{ item === '全部' ? '全部' : formatMonth(item) }}
      </text>
    </view>

    <view v-if="events.length" class="export-row">
      <button size="mini" class="export-btn" :disabled="exporting.busy" @click="exportAlbum">
        {{ exporting.busy ? `导出中 ${exporting.done}/${exporting.total}` : '⬇ 导出纪念册' }}
      </button>
      <text class="export-hint">单文件网页，发给家人双击即开，可打印成 PDF</text>
    </view>

    <view v-if="error" class="state"><text>{{ error }}</text><button size="mini" @click="load">重新加载</button></view>
    <view v-else-if="loading" class="state">
      <text>正在整理家里的照片…</text>
      <text v-if="progress.total" class="progress">{{ progress.done }} / {{ progress.total }}</text>
    </view>
    <view v-else-if="!visibleGroups.length" class="state">
      <text>{{ events.length ? '这个月还没有照片，换个月份看看' : '还没有带照片的相册或动态' }}</text>
      <text class="hint">去「相册」建一本，或在「动态」里发几张照片就会出现在这里。</text>
    </view>

    <view v-else class="axis">
      <view v-for="group in visibleGroups" :key="group.key" class="group">
        <view class="month-head">
          <text class="dot"></text>
          <text class="month-label">{{ group.label }}</text>
          <text class="month-count">{{ group.items.length }} 个时刻</text>
        </view>
        <view v-for="item in group.items" :key="item.source + '-' + item.id" class="entry">
          <view class="entry-head">
            <text class="when">{{ formatDay(item.date) }}</text>
            <text class="from">{{ item.sourceIcon }} {{ item.source }}</text>
            <text class="title">{{ item.title }}</text>
          </view>
          <text v-if="item.body" class="note">{{ item.body }}</text>
          <view class="grid">
            <image v-for="(url, index) in item.images" :key="index" class="cell" :src="url" mode="aspectFill" @click="preview(item, index)" />
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.timeline{display:flex;flex-direction:column;gap:16px}
.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.metric{background:rgba(255,255,255,.54);border:1px solid rgba(255,255,255,.8);border-radius:24px;padding:14px;text-align:center}
.figure{display:block;font-size:22px;font-weight:700;color:var(--c-primary)}
.caption{font-size:11px;color:var(--c-muted)}
.months{display:flex;flex-wrap:wrap;gap:8px}
.export-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.export-btn{font-size:12px;color:var(--c-primary);background:var(--c-primary-soft);border-radius:12px;padding:4px 12px;line-height:1.6}
.export-btn[disabled]{opacity:.6}
.export-hint{font-size:11px;color:var(--c-muted)}
.month-chip{font-size:12px;color:var(--c-ink-2);background:var(--c-surface-2);border-radius:12px;padding:5px 11px}
.month-chip.active{background:var(--c-primary);color:var(--c-surface)}
.axis{border-left:2px solid var(--c-sky-soft);padding-left:22px;margin-left:8px;display:flex;flex-direction:column;gap:26px}
.group{position:relative}
.month-head{display:flex;align-items:center;gap:10px;margin-bottom:14px}
.dot{position:absolute;left:-29px;width:11px;height:11px;border-radius:50%;background:var(--c-primary);box-shadow:0 0 0 3px var(--c-sky-soft)}
.month-label{font-size:15px;font-weight:700;color:var(--c-ink)}
.month-count{font-size:11px;color:var(--c-muted)}
.entry{margin-bottom:18px}
.entry-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.when{font-size:11px;font-weight:700;color:var(--c-surface);background:var(--c-sky);border-radius:8px;padding:3px 8px}
.from{font-size:11px;color:var(--c-muted)}
.title{font-size:13px;font-weight:600;color:var(--c-ink)}
.note{display:block;font-size:11px;color:var(--c-muted);margin-top:5px;line-height:1.6}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}
.cell{width:100%;aspect-ratio:1;border-radius:12px;background:var(--c-primary-soft)}
.state{text-align:center;color:var(--c-muted);font-size:13px;padding:34px 10px;display:flex;flex-direction:column;gap:8px;align-items:center}
.state button{font-size:11px}
.progress{font-size:12px;color:var(--c-primary)}
.hint{font-size:11px;color:var(--c-muted);line-height:1.7}
@media(max-width:700px){.grid{grid-template-columns:repeat(3,1fr);gap:6px}.axis{padding-left:16px;margin-left:4px}.dot{left:-23px}}
</style>
