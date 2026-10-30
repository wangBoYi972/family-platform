<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

/**
 * H5/APP WebView 下的视频渲染：uni-app 的 <video> 组件不支持绑定 MediaStream，
 * 因此直接向容器注入原生 video 元素。仅适用于 H5 与 App WebView（本项目通话端的范围）。
 */
const props = defineProps({
  stream: { type: Object, default: null },
  muted: { type: Boolean, default: false },
  mirror: { type: Boolean, default: false }
});

const box = ref(null);
let video = null;

function host() {
  const node = box.value;
  return node?.$el || node || null;
}

function sync() {
  if (typeof document === "undefined") return;
  const container = host();
  if (!container) return;
  if (!video || !container.contains(video)) {
    video = document.createElement("video");
    video.autoplay = true;
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.setAttribute("webkit-playsinline", "");
    Object.assign(video.style, {
      width: "100%",
      height: "100%",
      objectFit: "cover",
      display: "block",
      background: "#241f1c"
    });
    container.appendChild(video);
  }
  video.muted = props.muted;
  video.style.transform = props.mirror ? "scaleX(-1)" : "none";
  if (props.stream && video.srcObject !== props.stream) {
    video.srcObject = props.stream;
    const played = video.play();
    if (played?.catch) played.catch(() => {});
  }
  if (!props.stream) video.srcObject = null;
}

watch(() => [props.stream, props.muted, props.mirror], () => sync(), { flush: "post" });
onMounted(() => sync());
onBeforeUnmount(() => {
  if (video) {
    video.srcObject = null;
    video.remove();
    video = null;
  }
});
</script>

<template>
  <view ref="box" class="call-video"></view>
</template>

<style scoped>
.call-video {
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: inherit;
  background: #241f1c;
}
</style>
