<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

/**
 * 远端音频播放。
 *
 * 通话里音频轨一直存在，但此前只有 CallVideo 承担播放——它是按
 * `kind === 'VIDEO' && tile.cam` 渲染的，于是出现两个「无声」场景：
 *   1. 语音通话（AUDIO）根本没有 video 元素，远端音轨没有任何播放载体；
 *   2. 视频通话中对方关掉摄像头，tile 退化成头像占位，声音跟着一起消失。
 * 这里用原生 <audio> 单独承载远端音频，与画面是否显示解耦。
 */
const props = defineProps({
  stream: { type: Object, default: null }
});

const box = ref(null);
let audio = null;
let retryBound = false;

function host() {
  const node = box.value;
  return node?.$el || node || null;
}

function play() {
  if (!audio) return;
  const played = audio.play();
  if (!played?.catch) return;
  played.catch(() => {
    // 移动端浏览器会拦截无手势的自动播放，等下一次点击/触摸再补一次
    if (retryBound || typeof document === "undefined") return;
    retryBound = true;
    const retry = () => {
      document.removeEventListener("pointerdown", retry, true);
      audio?.play?.().catch(() => {});
    };
    document.addEventListener("pointerdown", retry, true);
  });
}

function sync() {
  if (typeof document === "undefined") return;
  const container = host();
  if (!container) return;
  if (!audio || !container.contains(audio)) {
    audio = document.createElement("audio");
    audio.autoplay = true;
    audio.playsInline = true;
    audio.setAttribute("playsinline", "");
    audio.setAttribute("webkit-playsinline", "");
    audio.style.display = "none";
    container.appendChild(audio);
  }
  if (props.stream && audio.srcObject !== props.stream) {
    audio.srcObject = props.stream;
    play();
  }
  if (!props.stream) audio.srcObject = null;
}

watch(() => props.stream, () => sync(), { flush: "post" });
onMounted(() => sync());
onBeforeUnmount(() => {
  if (audio) {
    audio.srcObject = null;
    audio.remove();
    audio = null;
  }
});
</script>

<template>
  <view ref="box" class="call-audio"></view>
</template>

<style scoped>
.call-audio {
  display: none;
}
</style>
