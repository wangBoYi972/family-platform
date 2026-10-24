package com.familyhome.content.service;

import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.vo.TaskVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface TaskService {
    List<TaskVO> list(SessionPrincipal p);

    TaskVO create(SessionPrincipal p, String bearer, TaskCreateDTO i);

    TaskVO update(SessionPrincipal p, String bearer, long id, TaskUpdateDTO i);

    void status(SessionPrincipal p, long id, String status);

    void delete(SessionPrincipal p, long id);
}
