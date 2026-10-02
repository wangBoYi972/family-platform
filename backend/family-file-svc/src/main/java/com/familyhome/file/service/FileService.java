package com.familyhome.file.service;

import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.io.IOException;
import java.util.List;

public interface FileService {
    List<FileVO> list(SessionPrincipal principal);

    FileVO upload(SessionPrincipal principal, FileUploadDTO input) throws IOException;

    FileVO metadata(SessionPrincipal principal, String id);

    FileDownloadVO download(SessionPrincipal principal, String id);

    void delete(SessionPrincipal principal, String id);
}
