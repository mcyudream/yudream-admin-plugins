package online.yudream.base.plugin.activityproof.interfaces.http;

import online.yudream.base.plugin.activityproof.application.dto.ActivityProofDownloadDTO;
import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.activityproof.interfaces.assembler.ActivityProofWebAssembler;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityParticipantAddRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofExportRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofMappingSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofSettingsSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofStampedPdfUploadRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityProofTemplateSelectRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivitySaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.request.ActivityTemplateMembersSaveRequest;
import online.yudream.base.plugin.activityproof.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class ActivityProofHttpFacade {

    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ActivityProofAppService appService;
    private final ActivityProofWebAssembler assembler = new ActivityProofWebAssembler();

    public ActivityProofHttpFacade(ActivityProofAppService appService) {
        this.appService = appService;
    }

    // ---------------------------------------------------------------- admin: status/settings/templates

    public PluginHttpResponse status() {
        return PluginHttpResponse.ok(appService.status());
    }

    public PluginHttpResponse servers() {
        return PluginHttpResponse.ok(appService.servers());
    }

    public PluginHttpResponse settings() {
        return PluginHttpResponse.ok(appService.settings());
    }

    public PluginHttpResponse templates(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.templates(firstQuery(request, "keyword"), page(request), size(request)));
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        ActivityProofSettingsSaveRequest body = JsonSupport.read(request.body(), ActivityProofSettingsSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveSettings(assembler.toCmd(body)));
    }

    public PluginHttpResponse selectTemplate(PluginHttpRequest request) {
        ActivityProofTemplateSelectRequest body = JsonSupport.read(request.body(), ActivityProofTemplateSelectRequest.class);
        return PluginHttpResponse.ok(appService.selectTemplate(assembler.toCmd(body)));
    }

    // ---------------------------------------------------------------- admin: option selectors

    public PluginHttpResponse departments(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.departmentOptions(firstQuery(request, "keyword")));
    }

    public PluginHttpResponse forms(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.formOptions(firstQuery(request, "keyword"), page(request), size(request)));
    }

    public PluginHttpResponse userOptions(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.userOptions(firstQuery(request, "keyword"), page(request), size(request)));
    }

    public PluginHttpResponse templateMembers(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.templateMembers(firstQuery(request, "templateId")));
    }

    public PluginHttpResponse saveTemplateMembers(PluginHttpRequest request) {
        ActivityTemplateMembersSaveRequest body = JsonSupport.read(request.body(), ActivityTemplateMembersSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveTemplateMembers(assembler.toCmd(body), currentUserId(request)));
    }

    public PluginHttpResponse qqConnections() {
        return PluginHttpResponse.ok(appService.qqConnections());
    }

    public PluginHttpResponse qqGroups(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.qqGroups(firstQuery(request, "connectionId")));
    }

    // ---------------------------------------------------------------- admin: activities

    public PluginHttpResponse activities(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.adminActivities(firstQuery(request, "keyword"), firstQuery(request, "status"), page(request), size(request)));
    }

    public PluginHttpResponse activity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.adminActivity(pathSegment(request.path(), 2)));
    }

    public PluginHttpResponse createActivity(PluginHttpRequest request) {
        ActivitySaveRequest body = JsonSupport.read(request.body(), ActivitySaveRequest.class);
        return PluginHttpResponse.ok(appService.createActivity(assembler.toCmd(body), currentUserId(request)));
    }

    public PluginHttpResponse updateActivity(PluginHttpRequest request) {
        ActivitySaveRequest body = JsonSupport.read(request.body(), ActivitySaveRequest.class);
        return PluginHttpResponse.ok(appService.updateActivity(assembler.toCmd(body)));
    }

    public PluginHttpResponse publishActivity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.publishActivity(pathSegment(request.path(), 2)));
    }

    public PluginHttpResponse closeActivity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.closeActivity(pathSegment(request.path(), 2)));
    }

    public PluginHttpResponse deleteActivity(PluginHttpRequest request) {
        appService.deleteActivity(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---------------------------------------------------------------- admin: participants & verification

    public PluginHttpResponse activityParticipants(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.adminParticipants(pathSegment(request.path(), 2), page(request), size(request)));
    }

    public PluginHttpResponse addParticipant(PluginHttpRequest request) {
        ActivityParticipantAddRequest body = JsonSupport.read(request.body(), ActivityParticipantAddRequest.class);
        return PluginHttpResponse.ok(appService.addParticipant(assembler.toCmd(pathSegment(request.path(), 2), body)));
    }

    public PluginHttpResponse removeParticipant(PluginHttpRequest request) {
        appService.removeParticipant(pathSegment(request.path(), 2), pathSegment(request.path(), 4));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse verifyParticipant(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.verifyParticipant(pathSegment(request.path(), 2), pathSegment(request.path(), 4)));
    }

    public PluginHttpResponse verifyAllParticipants(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.verifyAllParticipants(pathSegment(request.path(), 2)));
    }

    // ---------------------------------------------------------------- admin: mappings

    public PluginHttpResponse mappings(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.mappings(firstQuery(request, "serverId"), page(request), size(request)));
    }

    public PluginHttpResponse saveMapping(PluginHttpRequest request) {
        ActivityProofMappingSaveRequest body = JsonSupport.read(request.body(), ActivityProofMappingSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveMapping(assembler.toCmd(body)));
    }

    public PluginHttpResponse deleteMapping(PluginHttpRequest request) {
        appService.deleteMapping(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---------------------------------------------------------------- admin: exports

    public PluginHttpResponse export(PluginHttpRequest request) {
        ActivityProofExportRequest body = JsonSupport.read(request.body(), ActivityProofExportRequest.class);
        return PluginHttpResponse.ok(appService.export(assembler.toCmd(body), currentUserId(request)));
    }

    public PluginHttpResponse exports(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.exportRecords(page(request), size(request)));
    }

    public PluginHttpResponse uploadStampedPdf(PluginHttpRequest request) {
        ActivityProofStampedPdfUploadRequest body = JsonSupport.read(request.body(), ActivityProofStampedPdfUploadRequest.class);
        return PluginHttpResponse.ok(appService.uploadStampedPdf(assembler.toCmd(pathSegment(request.path(), 2), body)));
    }

    public PluginHttpResponse deleteExport(PluginHttpRequest request) {
        appService.deleteExportRecord(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse download(PluginHttpRequest request) {
        return downloadResponse(appService.downloadWord(pathSegment(request.path(), 2)), DOCX_CONTENT_TYPE);
    }

    public PluginHttpResponse downloadStampedPdf(PluginHttpRequest request) {
        return downloadResponse(appService.downloadStampedPdf(pathSegment(request.path(), 2)), "application/pdf");
    }

    // ---------------------------------------------------------------- user: square & participation

    public PluginHttpResponse myActivities(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.userActivities(currentUserId(request), page(request), size(request)));
    }

    public PluginHttpResponse myActivity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.userActivity(pathSegment(request.path(), 2), currentUserId(request)));
    }

    public PluginHttpResponse joinActivity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.joinActivity(pathSegment(request.path(), 2), currentUserId(request)));
    }

    public PluginHttpResponse cancelActivity(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.cancelActivity(pathSegment(request.path(), 2), currentUserId(request)));
    }

    public PluginHttpResponse myParticipations(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.myParticipations(currentUserId(request), page(request), size(request)));
    }

    public PluginHttpResponse verifyMyParticipation(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.verifyMyParticipation(pathSegment(request.path(), 2), currentUserId(request)));
    }

    // ---------------------------------------------------------------- user: exports

    public PluginHttpResponse myExports(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.myStampedExportRecords(currentUserId(request), page(request), size(request)));
    }

    public PluginHttpResponse downloadMyStampedPdf(PluginHttpRequest request) {
        return downloadResponse(appService.downloadMyStampedPdf(pathSegment(request.path(), 2), currentUserId(request)), "application/pdf");
    }

    // ---------------------------------------------------------------- internals

    private PluginHttpResponse downloadResponse(ActivityProofDownloadDTO download, String defaultContentType) {
        try (var inputStream = download.file().inputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            return new PluginHttpResponse(
                    200,
                    Map.of(
                            "Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(download.filename(), StandardCharsets.UTF_8),
                            "Cache-Control", "no-cache"
                    ),
                    download.contentType() == null || download.contentType().isBlank() ? defaultContentType : download.contentType(),
                    bytes,
                    false
            );
        } catch (IOException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "文件读取失败：" + e.getMessage()));
        }
    }

    private String currentUserId(PluginHttpRequest request) {
        return request.principal() == null || request.principal().userId() == null
                ? ""
                : String.valueOf(request.principal().userId());
    }

    private int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private int size(PluginHttpRequest request) {
        return intQuery(request, "size", 20);
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank()
                ? defaultValue
                : Integer.parseInt(values.get(0));
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String pathSegment(String path, int index) {
        String[] segments = trim(path).split("/");
        return index >= 0 && index < segments.length ? decode(segments[index]) : null;
    }

    private String trim(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
