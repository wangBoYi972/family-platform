<script setup>
import { computed, ref } from "vue";
import { dayCaption, monthCells } from "@shared/calendar-marks.mjs";

const today = new Date();
const cursor = ref(new Date(today.getFullYear(), today.getMonth(), 1));
const selected = ref(format(today));
const heading = computed(() => `${cursor.value.getFullYear()} 年 ${cursor.value.getMonth() + 1} 月`);
const cells = computed(() => monthCells(cursor.value.getFullYear(), cursor.value.getMonth()));
const caption = computed(() => dayCaption(selected.value));

function format(date) {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}
function move(offset) {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + offset, 1);
  selected.value = format(cursor.value);
}
</script>

<template>
  <section class="board">
    <div class="month">
      <button type="button" aria-label="上个月" @click="move(-1)">‹</button>
      <strong>{{ heading }}</strong>
      <button type="button" aria-label="下个月" @click="move(1)">›</button>
    </div>
    <div class="grid">
      <span v-for="day in ['一','二','三','四','五','六','日']" :key="day" class="weekday">{{ day }}</span>
      <span v-for="(cell, index) in cells" :key="cell?.iso || 'blank' + index">
        <button v-if="cell" type="button" :class="['day', { selected: selected === cell.iso, rest: cell.rest, festival: cell.festival, term: cell.term, work: cell.work }]" @click="selected = cell.iso">
          <span class="solar">{{ cell.day }}</span>
          <span class="lunar">{{ cell.lunarShort }}</span>
          <span class="extra">{{ cell.highlight }}</span>
          <span v-if="cell.badge" class="badge">{{ cell.badge }}</span>
        </button>
      </span>
    </div>
    <p class="caption">{{ selected }} · {{ caption }}</p>
    <p class="legend">日期下是农历。节日和节气写在第三行，休是放假，班是补班。</p>
  </section>
</template>

<style scoped>
.board { margin-top: 22px; padding: 16px 16px 12px; background: rgba(255,255,255,.54); border: 1px solid rgba(255,255,255,.8); border-radius: 30px; backdrop-filter: blur(20px); }
.month, .grid { display: grid; }
.month { grid-template-columns: 36px 1fr 36px; align-items: center; text-align: center; }
.month button { height: 32px; border: 1px solid rgba(255,255,255,.8); background: rgba(255,255,255,.54); color: #142B53; border-radius: 30px; }
.grid { grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 4px; margin-top: 14px; text-align: center; }
.weekday { font-size: 12px; color: var(--family-muted); padding-bottom: 6px; }
.day { position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center; width: 100%; height: 62px; padding: 4px 0 6px; border: 0; background: transparent; color: #2c2824; line-height: 1.1; }
.solar { font-size: 14px; }
.lunar, .extra { min-height: 12px; font-size: 10px; font-weight: 400; }
.lunar { color: var(--family-muted); }
.extra { color: transparent; }
.festival .extra, .rest .extra, .rest .solar { color: var(--family-primary); }
.term .extra { color: #6f675f; }
.badge { position: absolute; top: 2px; right: 3px; font-size: 9px; line-height: 1; color: var(--family-primary); }
.work .badge { color: #6f675f; }
.selected { background: #4F7DC6; color: #F4F7FF; }
.selected .lunar, .selected .extra, .selected .badge { color: #f3ece3; }
.caption, .legend { margin: 10px 0 0; font-size: 12px; color: var(--family-muted); line-height: 1.6; }
.caption { color: #2c2824; }
</style>
