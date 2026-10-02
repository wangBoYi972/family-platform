package com.familyhome.file.converter;

import com.familyhome.file.model.FamilyFile;
import com.familyhome.file.vo.FileVO;

public final class FileConverter {
    private FileConverter() {}

    public static FileVO toVO(FamilyFile f) {
        return new FileVO(
                f.getId(),
                f.getOwnerId(),
                f.getName(),
                f.getMime(),
                f.getSizeBytes(),
                f.getVisibility(),
                f.getCreatedAt());
    }
}
