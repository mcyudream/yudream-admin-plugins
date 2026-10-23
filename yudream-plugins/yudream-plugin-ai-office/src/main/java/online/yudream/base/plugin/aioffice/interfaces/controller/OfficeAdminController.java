package online.yudream.base.plugin.aioffice.interfaces.controller;

import online.yudream.base.plugin.aioffice.interfaces.http.OfficeHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** AI 模板办公·管理面。 */
public class OfficeAdminController {

    private final OfficeHttpFacade http;

    public OfficeAdminController(OfficeHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse settings(PluginHttpRequest request) {
        return http.settings(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.settings(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/schemas", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse schemas(PluginHttpRequest request) {
        return http.schemas(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/schemas", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse saveSchema(PluginHttpRequest request) {
        return http.schemas(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/schemas/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updateSchema(PluginHttpRequest request) {
        return http.schemaItem(request, segmentAfter(request.path(), "schemas"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/schemas/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deleteSchema(PluginHttpRequest request) {
        return http.schemaItem(request, segmentAfter(request.path(), "schemas"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/doc-types", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse docTypes(PluginHttpRequest request) {
        return http.docTypes(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/doc-types", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse saveDocType(PluginHttpRequest request) {
        return http.docTypes(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/doc-types/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updateDocType(PluginHttpRequest request) {
        return http.docTypeItem(request, segmentAfter(request.path(), "doc-types"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/doc-types/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deleteDocType(PluginHttpRequest request) {
        return http.docTypeItem(request, segmentAfter(request.path(), "doc-types"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/prompts", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse prompts(PluginHttpRequest request) {
        return http.prompts(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/prompts", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse savePrompt(PluginHttpRequest request) {
        return http.prompts(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/prompts/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updatePrompt(PluginHttpRequest request) {
        return http.promptItem(request, segmentAfter(request.path(), "prompts"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/prompts/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deletePrompt(PluginHttpRequest request) {
        return http.promptItem(request, segmentAfter(request.path(), "prompts"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/categories", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse categories(PluginHttpRequest request) {
        return http.categories(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/categories", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse saveCategory(PluginHttpRequest request) {
        return http.categories(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/categories/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updateCategory(PluginHttpRequest request) {
        return http.categoryItem(request, segmentAfter(request.path(), "categories"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/categories/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deleteCategory(PluginHttpRequest request) {
        return http.categoryItem(request, segmentAfter(request.path(), "categories"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/packages", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse packages(PluginHttpRequest request) {
        return http.packages(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/packages", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse savePackage(PluginHttpRequest request) {
        return http.packages(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/packages/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updatePackage(PluginHttpRequest request) {
        return http.packageItem(request, segmentAfter(request.path(), "packages"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/packages/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deletePackage(PluginHttpRequest request) {
        return http.packageItem(request, segmentAfter(request.path(), "packages"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/packages/{code}/precheck",
            permission = OfficePermissions.GENERATE)
    public PluginHttpResponse packagePrecheck(PluginHttpRequest request) {
        return http.packagePrecheck(request, segmentAfter(request.path(), "packages"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/packages/{code}/export",
            permission = OfficePermissions.GENERATE, wrapResult = false)
    public PluginHttpResponse packageExport(PluginHttpRequest request) {
        return http.packageExport(request, segmentAfter(request.path(), "packages"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/dataset", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse dataset(PluginHttpRequest request) {
        return http.dataset(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/dataset", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse saveDataset(PluginHttpRequest request) {
        return http.dataset(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/dataset/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse updateDataset(PluginHttpRequest request) {
        return http.datasetItem(request, segmentAfter(request.path(), "dataset"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/dataset/{id}", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse deleteDataset(PluginHttpRequest request) {
        return http.datasetItem(request, segmentAfter(request.path(), "dataset"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/word-templates", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse wordTemplates(PluginHttpRequest request) {
        return http.wordTemplates(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/ai/providers", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse aiProviders(PluginHttpRequest request) {
        return http.aiProviders(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/presets", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse presetList(PluginHttpRequest request) {
        return http.presetList(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/presets/import", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse presetImport(PluginHttpRequest request) {
        return http.presetImport(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/presets/upload", permission = OfficePermissions.MANAGE)
    public PluginHttpResponse presetUpload(PluginHttpRequest request) {
        return http.presetUpload(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/presets/export", permission = OfficePermissions.MANAGE,
            wrapResult = false)
    public PluginHttpResponse presetExport(PluginHttpRequest request) {
        return http.presetExport(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/finance", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse finance(PluginHttpRequest request) {
        return http.adminFinance(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/finance", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse saveFinance(PluginHttpRequest request) {
        return http.adminFinance(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/finance/{id}/review", permission = OfficePermissions.ACCEPT)
    public PluginHttpResponse reviewFinance(PluginHttpRequest request) {
        return http.adminFinanceReview(request, segmentAfter(request.path(), "finance"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/finance/{id}", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse deleteFinance(PluginHttpRequest request) {
        return http.adminFinanceItem(request, segmentAfter(request.path(), "finance"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settlements", permission = OfficePermissions.SETTLE)
    public PluginHttpResponse settlements(PluginHttpRequest request) {
        return http.settlements(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settlements/overview", permission = OfficePermissions.SETTLE)
    public PluginHttpResponse settlementOverview(PluginHttpRequest request) {
        return http.settlementOverview(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/settlements/close", permission = OfficePermissions.SETTLE)
    public PluginHttpResponse settlementClose(PluginHttpRequest request) {
        return http.settlementClose(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/generate/context", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse generateContext(PluginHttpRequest request) {
        return http.generateContext(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/generate/preview", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse generatePreview(PluginHttpRequest request) {
        return http.generatePreview(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/generate/jobs", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse generateJobs(PluginHttpRequest request) {
        return http.generateJobs(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/generate/jobs", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse createJob(PluginHttpRequest request) {
        return http.generateJobs(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/generate/jobs/{id}", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse jobItem(PluginHttpRequest request) {
        return http.generateJobItem(request, segmentAfter(request.path(), "jobs"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/generate/jobs/{id}", permission = OfficePermissions.GENERATE)
    public PluginHttpResponse deleteJob(PluginHttpRequest request) {
        return http.generateJobItem(request, segmentAfter(request.path(), "jobs"));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/generate/jobs/{id}/ai",
            permission = OfficePermissions.GENERATE)
    public PluginHttpResponse jobAi(PluginHttpRequest request) {
        return http.generateJobAi(request, segmentAfter(request.path(), "jobs"));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/generate/jobs/{id}/draft",
            permission = OfficePermissions.GENERATE)
    public PluginHttpResponse jobDraft(PluginHttpRequest request) {
        return http.generateJobDraft(request, segmentAfter(request.path(), "jobs"));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/generate/jobs/{id}/render",
            permission = OfficePermissions.GENERATE)
    public PluginHttpResponse jobRender(PluginHttpRequest request) {
        return http.generateJobRender(request, segmentAfter(request.path(), "jobs"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/generate/jobs/{id}/download",
            permission = OfficePermissions.GENERATE, wrapResult = false)
    public PluginHttpResponse jobDownload(PluginHttpRequest request) {
        return http.downloadJob(request, segmentAfter(request.path(), "jobs"));
    }

    private static String segmentAfter(String path, String marker) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (marker.equals(segments[i])) {
                return URLDecoder.decode(segments[i + 1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
