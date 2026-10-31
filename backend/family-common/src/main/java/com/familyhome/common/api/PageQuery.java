package com.familyhome.common.api;

/**
 * 列表接口的分页参数。
 *
 * <p>背景：各服务的列表接口此前一律写死 {@code LIMIT 500}，接口既没有分页参数也不告诉客户端被截断了，
 * 家庭数据攒够 500 条之后旧数据会被静默丢弃。
 *
 * <p>这里保持向后兼容：不传 {@code page}/{@code size} 时等价于原来的「取前 500 条」，
 * 老客户端行为不变；需要翻页的客户端传 {@code ?page=2&size=100} 即可。
 */
public record PageQuery(int page, int size) {

    /** 单页上限：与历史 LIMIT 500 对齐，避免一次拉爆内存。 */
    public static final int MAX_SIZE = 500;

    public static final int DEFAULT_SIZE = 500;

    public static PageQuery of(Integer page, Integer size) {
        return of(page, size, DEFAULT_SIZE);
    }

    public static PageQuery of(Integer page, Integer size, int defaultSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? defaultSize : size;
        if (s > MAX_SIZE) s = MAX_SIZE;
        return new PageQuery(p, s);
    }

    /** 第一页（默认页大小），等价于改造前的行为。 */
    public static PageQuery first() {
        return new PageQuery(1, DEFAULT_SIZE);
    }

    public boolean isFirstPage() {
        return page == 1;
    }

    public long offset() {
        return (long) (page - 1) * size;
    }

    /** 拼进 QueryWrapper 的 last 片段；只由整数拼成，无注入面。 */
    public String limitClause() {
        return "LIMIT " + size + " OFFSET " + offset();
    }
}
