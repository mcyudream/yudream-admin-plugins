package online.yudream.base.plugin.activityproof.interfaces.assembler;

import online.yudream.base.plugin.activityproof.application.cmd.ActivityBindingCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityParticipantAddCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofExportCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofMappingSaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofSettingsSaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofStampedPdfUploadCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofTemplateSelectCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityQuizSaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivitySaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityTemplateMembersSaveCmd;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityBindingRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityParticipantAddRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofExportRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofMappingSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofSettingsSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofStampedPdfUploadRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofTemplateSelectRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityQuizSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivitySaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityTemplateMembersSaveRequest;

import java.util.List;

public class ActivityProofWebAssembler {

    public ActivityProofTemplateSelectCmd toCmd(ActivityProofTemplateSelectRequest request) {
        return new ActivityProofTemplateSelectCmd(request.templateId());
    }

    public ActivityProofSettingsSaveCmd toCmd(ActivityProofSettingsSaveRequest request) {
        return new ActivityProofSettingsSaveCmd(request.defaultActivityName(), request.defaultCollege(), request.defaultIssuer(),
                request.templateId(), request.qqNotifyEnabled(), request.qqConnectionId(), request.qqGroupIds(), request.qqMessageTemplate());
    }

    public ActivityParticipantAddCmd toCmd(String activityId, ActivityParticipantAddRequest request) {
        return new ActivityParticipantAddCmd(activityId, request.userId(), request.passed(), request.note());
    }

    public ActivityTemplateMembersSaveCmd toCmd(ActivityTemplateMembersSaveRequest request) {
        return new ActivityTemplateMembersSaveCmd(request.templateId(), request.userIds());
    }

    public ActivityProofMappingSaveCmd toCmd(ActivityProofMappingSaveRequest request) {
        return new ActivityProofMappingSaveCmd(request.serverId(), request.playerId(), request.playerName(), request.studentNo());
    }

    public ActivityQuizSaveCmd toCmd(ActivityQuizSaveRequest request) {
        return new ActivityQuizSaveCmd(request.enabled(), request.categoryId(), request.tags(), request.types(),
                request.difficulties(), request.count(), request.passCorrect(), request.subjectiveMode());
    }

    public ActivitySaveCmd toCmd(ActivitySaveRequest request) {
        List<ActivityBindingCmd> bindings = request.bindings() == null ? List.of() : request.bindings().stream()
                .map(this::toCmd)
                .toList();
        return new ActivitySaveCmd(
                request.id(),
                request.title(),
                request.summary(),
                request.description(),
                request.coverUrl(),
                request.signupStart(),
                request.signupEnd(),
                request.activityStart(),
                request.activityEnd(),
                request.deptMode(),
                request.allowedDeptIds(),
                bindings
        );
    }

    public ActivityBindingCmd toCmd(ActivityBindingRequest request) {
        return new ActivityBindingCmd(request.type(), request.serverId(), request.minOnlineMinutes(), request.includeAfk(),
                request.autoJoin(), request.formCode());
    }

    public ActivityProofExportCmd toCmd(ActivityProofExportRequest request) {
        return new ActivityProofExportCmd(
                request.activityId(),
                request.proofNo(),
                request.college(),
                request.issuer(),
                request.issueDate(),
                request.selectedUserIds()
        );
    }

    public ActivityProofStampedPdfUploadCmd toCmd(String id, ActivityProofStampedPdfUploadRequest request) {
        return new ActivityProofStampedPdfUploadCmd(id, request.filename(), request.contentType(), request.base64());
    }
}
