package com.familyhome.content.vo;

/**
 * 任务看板的汇总指标。
 *
 * <p>指标反映整个家庭的家底，不能靠「当前这一页」算：列表一旦分页，前端按已加载数据做统计会
 * 越翻越少。所以计数交给数据库 {@code COUNT(*)}，列表只管分页取数。
 */
public record TaskStatsVO(long total, long pending, long completed, long overdue) {}
