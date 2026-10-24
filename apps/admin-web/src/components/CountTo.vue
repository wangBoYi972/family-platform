<script setup>
import { onMounted, ref, watch } from "vue";

const props = defineProps({
  value: { type: Number, default: 0 },
  duration: { type: Number, default: 1000 },
  suffix: { type: String, default: "" }
});
const shown = ref(0);
let frame = 0;

function run(to) {
  cancelAnimationFrame(frame);
  const from = shown.value;
  const start = performance.now();
  const step = (now) => {
    const progress = Math.min((now - start) / props.duration, 1);
    const eased = 1 - Math.pow(1 - progress, 3);
    shown.value = Math.round(from + (to - from) * eased);
    if (progress < 1) frame = requestAnimationFrame(step);
  };
  frame = requestAnimationFrame(step);
}

watch(() => props.value, run);
onMounted(() => run(props.value));
</script>

<template>
  <span class="mf-num">{{ shown }}{{ suffix }}</span>
</template>
