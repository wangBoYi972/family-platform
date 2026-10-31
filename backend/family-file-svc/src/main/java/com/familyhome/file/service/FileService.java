package com.familyhome.file.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.io.IOException;
import java.util.List;

public interface FileService {
    /** 分页查询；不传分页参数时等价于改造前的「取前 500 条」。 */
    List<FileVO> list(SessionPrincipal principal, PageQuery page);

    default List<FileVO> list(SessionPrincipal principal) {
        return list(principal, PageQuery.first());
    }

    FileVO upload(SessionPrincipal principal, FileUploadDTO input) throws IOException;

    FileVO metadata(SessionPrincipal principal, String id);

    FileDownloadVO download(SessionPrincipal principal, String id);

    void delete(SessionPrincipal principal, String id);
}
