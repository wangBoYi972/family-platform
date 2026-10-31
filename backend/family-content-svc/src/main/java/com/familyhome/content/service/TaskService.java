package com.familyhome.content.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.vo.TaskVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface TaskService {
    /**
     * 分页 + 筛选查询；不传分页参数时等价于改造前的「取前 500 条」。
     *
     * <p>筛选下推到 SQL：前端筛选只对已加载的一页生效，翻页时会把「符合条件但还没加载」
     * 的任务彻底藏起来，所以必须由数据库判断。
     *
     * @param keyword 标题或负责人姓名模糊匹配，空值表示不筛
     * @param status 全部（null / ALL）、TODO、IN_PROGRESS、COMPLETED 或 OVERDUE
     */
    List<TaskVO> list(SessionPrincipal p, PageQuery page, String keyword, String status);

    default List<TaskVO> list(SessionPrincipal p, PageQuery page) {
        return list(p, page, null, null);
    }

    default List<TaskVO> list(SessionPrincipal p) {
        return list(p, PageQuery.first(), null, null);
    }

    TaskVO create(SessionPrincipal p, String bearer, TaskCreateDTO i);

    TaskVO update(SessionPrincipal p, String bearer, long id, TaskUpdateDTO i);

    void status(SessionPrincipal p, long id, String status);

    void delete(SessionPrincipal p, long id);
}
