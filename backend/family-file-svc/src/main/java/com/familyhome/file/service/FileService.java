package com.familyhome.file.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.io.IOException;
import java.util.List;

public interface FileService {
    /** 分页 + 关键字查询；不传分页参数时等价于改造前的「取前 500 条」。keyword 匹配文件名或 MIME。 */
    List<FileVO> list(SessionPrincipal principal, PageQuery page, String keyword);

    default List<FileVO> list(SessionPrincipal principal, PageQuery page) {
        return list(principal, page, null);
    }

    default List<FileVO> list(SessionPrincipal principal) {
        return list(principal, PageQuery.first(), null);
    }

    FileVO upload(SessionPrincipal principal, FileUploadDTO input) throws IOException;

    FileVO metadata(SessionPrincipal principal, String id);

    FileDownloadVO download(SessionPrincipal principal, String id);

    void delete(SessionPrincipal principal, String id);
}
