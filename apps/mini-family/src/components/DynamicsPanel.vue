<script setup>
import { ref, onMounted } from "vue";
import { request, upload, loadImageUrl } from "../../utils/request";

const props = defineProps({ token: String });
const entries = ref([]);
const loading = ref(false);
const publishing = ref(false);
const form = ref({ title: "", body: "" });
const images = ref([]); // { tempFilePath, name }

async function load() {
  if (!props.token) return;
  loading.value = true;
  try {
    entries.value = await request("/dynamics");
    await Promise.all(
      entries.value.map(async item => {
        item.images = [];
        if (!item.fileIds) return;
        const ids = String(item.fileIds).split(",").filter(Boolean);
        for (const id of ids.slice(0, 9)) {
          try {
            item.images.push(await loadImageUrl(id));
          } catch (error) {
            // 单张图片失败不阻塞列表
          }
        }
      })
    );
  } catch (error) {
    uni.showToast({ title: error.message || "加载失败", icon: "none" });
  } finally {
    loading.value = false;
  }
}

function chooseImages() {
  const remain = 9 - images.value.length;
  if (remain <= 0) return uni.showToast({ title: "最多 9 张图片", icon: "none" });
  uni.chooseImage({
    count: remain,
    sizeType: ["compressed"],
    success: res => {
      images.value.push(...res.tempFilePaths.map(p => ({ tempFilePath: p })));
    }
  });
}

function removeImage(index) {
  images.value.splice(index, 1);
}

async function publish() {
  if (publishing.value) return;
  const title = form.value.title.trim();
  const body = form.value.body.trim();
  if (!title || !body) return uni.showToast({ title: "标题和内容不能为空", icon: "none" });
  publishing.value = true;
  try {
    const fileIds = [];
    for (const image of images.value) {
      const file = await upload("/files", image.tempFilePath, { visibility: "FAMILY" });
      fileIds.push(file.id);
    }
    await request("/dynamics", {
      method: "POST",
      data: { title, body, visibility: "FAMILY", fileIds: fileIds.length ? fileIds.join(",") : undefined }
    });
    form.value = { title: "", body: "" };
    images.value = [];
    uni.showToast({ title: "已发布", icon: "none" });
    await load();
  } catch (error) {
    uni.showToast({ title: error.message || "发布失败", icon: "none" });
  } finally {
    publishing.value = false;
  }
}

defineExpose({ load });
onMounted(load);
</script>

<template>
  <view class="panel">
    <view class="toolbar">
      <text class="section">家庭动态</text>
      <text class="link" @click="publishing = !publishing">{{ publishing ? "收起" : "＋ 发布动态" }}</text>
    </view>

    <view v-if="publishing" class="form card">
      <input v-model="form.title" class="input" maxlength="120" placeholder="动态标题（必填）" />
      <textarea v-model="form.body" class="textarea" maxlength="10000" placeholder="分享这一刻…（必填）" :auto-height="true" />
      <view class="image-picker">
        <view v-for="(image, index) in images" :key="index" class="thumb-wrap">
          <image :src="image.tempFilePath" class="thumb" mode="aspectFill" />
          <text class="thumb-remove" @click="removeImage(index)">×</text>
        </view>
        <view v-if="images.length < 9" class="thumb-add" @click="chooseImages">＋</view>
      </view>
      <button class="primary" :loading="publishing" :disabled="publishing" @click="publish">发布动态</button>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!entries.length" class="empty">还没有动态，发布第一条吧</view>
    <view v-else class="list">
      <view v-for="item in entries" :key="item.id" class="row card">
        <text class="row-title">{{ item.title }}</text>
        <text v-if="item.body" class="row-body">{{ item.body }}</text>
        <view v-if="item.images && item.images.length" class="images">
          <image v-for="(url, index) in item.images" :key="index" :src="url" class="img" mode="aspectFill" @click="uni.previewImage({ urls: item.images, current: index })" />
        </view>
        <text class="row-meta">{{ item.likes ? item.likes + " 人喜欢" : "" }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.panel{display:flex;flex-direction:column;gap:24rpx}
.toolbar{display:flex;justify-content:space-between;align-items:center}
.section{font-size:36rpx;font-weight:600}
.link{color:var(--family-primary);font-size:28rpx}
.card{background:#fff;border-radius:16rpx;padding:26rpx;box-shadow:0 2rpx 12rpx rgba(29,42,38,.04)}
.form{display:flex;flex-direction:column;gap:20rpx}
.input{height:84rpx;padding:0 24rpx;background:#fbfcfb;border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}
.textarea{width:100%;min-height:160rpx;padding:20rpx 24rpx;background:#fbfcfb;border:1px solid var(--family-border);border-radius:12rpx;box-sizing:border-box}
.primary{margin-top:8rpx;background:var(--family-primary);color:#fff;border-radius:12rpx}
.image-picker{display:flex;flex-wrap:wrap;gap:16rpx}
.thumb-wrap{position:relative}
.thumb{width:150rpx;height:150rpx;border-radius:12rpx;background:#eef2f0}
.thumb-remove{position:absolute;top:-12rpx;right:-12rpx;width:40rpx;height:40rpx;line-height:36rpx;text-align:center;background:#d9534f;color:#fff;border-radius:50%;font-size:28rpx}
.thumb-add{width:150rpx;height:150rpx;line-height:150rpx;text-align:center;background:#f0f3f1;color:var(--family-muted);border:1px dashed var(--family-border);border-radius:12rpx;font-size:56rpx}
.row{display:flex;flex-direction:column;gap:14rpx}
.row-title{font-size:31rpx;font-weight:600}
.row-body{color:var(--family-text);font-size:27rpx;line-height:1.6;white-space:pre-wrap}
.images{display:flex;flex-wrap:wrap;gap:12rpx}
.img{width:200rpx;height:200rpx;border-radius:12rpx;background:#eef2f0}
.row-meta{color:var(--family-muted);font-size:24rpx}
.empty{padding:80rpx 0;text-align:center;color:var(--family-muted)}
</style>
