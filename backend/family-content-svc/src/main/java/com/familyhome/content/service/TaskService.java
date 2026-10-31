package com.familyhome.content.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.vo.TaskVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface TaskService {
    /** 分页查询；不传分页参数时等价于改造前的「取前 500 条」。 */
    List<TaskVO> list(SessionPrincipal p, PageQuery page);

    default List<TaskVO> list(SessionPrincipal p) {
        return list(p, PageQuery.first());
    }

    TaskVO create(SessionPrincipal p, String bearer, TaskCreateDTO i);

    TaskVO update(SessionPrincipal p, String bearer, long id, TaskUpdateDTO i);

    void status(SessionPrincipal p, long id, String status);

    void delete(SessionPrincipal p, long id);
}
