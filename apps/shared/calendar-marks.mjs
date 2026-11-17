import lunar from "lunar-javascript";

const { Solar, HolidayUtil } = lunar;

/** 格子里用短名，避免七列日历挤成一团。 */
const SHORT = {
  元旦节: "元旦",
  春节: "春节",
  清明节: "清明",
  劳动节: "劳动",
  端午节: "端午",
  中秋节: "中秋",
  国庆节: "国庆",
  国庆中秋: "国庆",
  元宵节: "元宵",
  龙头节: "龙头",
  七夕节: "七夕",
  重阳节: "重阳",
  腊八节: "腊八",
  除夕: "除夕",
  妇女节: "妇女",
  儿童节: "儿童"
};

const SOLAR_FESTIVALS = new Set(["元旦节", "妇女节", "劳动节", "儿童节", "国庆节"]);

function short(name) {
  return SHORT[name] || name;
}

function monthName(lunarDate) {
  const raw = lunarDate.getMonthInChinese();
  return raw.endsWith("月") ? raw : `${raw}月`;
}

/**
 * 某一天的农历与节假日标记。
 * 农历每天都有；传统节日优先于放假名，放假日再退回法定节日，最后才是节气。
 */
export function dayMark(iso) {
  const [year, month, day] = iso.split("-").map(Number);
  const solar = Solar.fromYmd(year, month, day);
  const lunarDate = solar.getLunar();
  const monthLabel = monthName(lunarDate);
  const lunarDay = lunarDate.getDayInChinese();
  const holiday = HolidayUtil.getHoliday(year, month, day);
  const lunarFestival = lunarDate.getFestivals()[0] || "";
  const solarFestival = solar.getFestivals().find(name => SOLAR_FESTIVALS.has(name)) || "";
  const term = lunarDate.getJieQi() || "";
  const rest = Boolean(holiday && !holiday.isWork());
  const work = Boolean(holiday && holiday.isWork());
  const highlight = lunarFestival
    ? short(lunarFestival)
    : rest
      ? short(holiday.getName())
      : solarFestival
        ? short(solarFestival)
        : term;
  return {
    lunar: `农历${monthLabel}${lunarDay}`,
    lunarShort: lunarDay === "初一" ? monthLabel : lunarDay,
    highlight,
    festival: Boolean(lunarFestival || rest || solarFestival),
    term: Boolean(!lunarFestival && !rest && !solarFestival && term),
    rest,
    work,
    badge: rest ? "休" : work ? "班" : ""
  };
}

/** 选中日期旁的说明：农历全称，再加上节日、节气或补班。 */
export function dayCaption(iso) {
  const mark = dayMark(iso);
  const tail = mark.highlight || (mark.work ? "补班" : "");
  return `${mark.lunar}${tail ? " · " + tail : ""}`;
}

/** 周一开头的月历格子，空白格为 null。 */
export function monthCells(year, monthIndex) {
  const start = (new Date(year, monthIndex, 1).getDay() + 6) % 7;
  const total = new Date(year, monthIndex + 1, 0).getDate();
  const cells = Array.from({ length: start }, () => null);
  for (let day = 1; day <= total; day += 1) {
    const iso = `${year}-${String(monthIndex + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
    cells.push({ iso, day, ...dayMark(iso) });
  }
  return cells;
}
