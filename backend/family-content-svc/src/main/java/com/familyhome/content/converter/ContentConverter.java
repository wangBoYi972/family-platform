package com.familyhome.content.converter;

import com.familyhome.content.model.ContentEntry;
import com.familyhome.content.model.FamilyTask;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.content.vo.TaskVO;

public final class ContentConverter {
    private ContentConverter() {}

    public static TaskVO toVO(FamilyTask e) {
        return new TaskVO(
                e.getId(),
                e.getTitle(),
                e.getCreatorId(),
                e.getAssigneeId(),
                e.getAssigneeName(),
                e.getDeadline(),
                e.getStatus(),
                e.getCreatedAt());
    }

    public static EntryVO toVO(ContentEntry e) {
        return new EntryVO(
                e.getId(),
                e.getCreatorId(),
                e.getTitle(),
                e.getBody(),
                e.getEventDate(),
                e.getYearly(),
                e.getPinned(),
                e.getVisibility(),
                e.getFileIds(),
                e.getLikes(),
                e.getCreatedAt());
    }
}
