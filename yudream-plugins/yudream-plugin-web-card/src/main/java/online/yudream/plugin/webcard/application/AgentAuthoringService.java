package online.yudream.plugin.webcard.application;

import com.fasterxml.jackson.core.type.TypeReference;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatMessage;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.webcard.domain.WebCardModels.*;
import online.yudream.plugin.webcard.domain.WebCardRepository;
import online.yudream.plugin.webcard.interfaces.JsonSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class AgentAuthoringService implements AutoCloseable {
    public static final String BUILTIN_AGENT = "builtin-web-card-studio";
    static final String PLAN_COMPILER_AGENT = "builtin-web-card-plan-compiler";
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\[\\]{}\"']+");
    private final WebCardRepository repository;
    private final WebCardApplicationService app;
    private final PluginAiService ai;
    private final FrameworkServices framework;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ManagedStream> streams = new ConcurrentHashMap<>();
    public AgentAuthoringService(WebCardRepository repository, WebCardApplicationService app, PluginAiService ai, FrameworkServices framework) { this.repository = repository; this.app = app; this.ai = ai; this.framework = framework; }

    public AgentSession create(AgentSession input) {
        long now = System.currentTimeMillis();
        AgentSession value = new AgentSession(input.id() == null || input.id().isBlank() ? UUID.randomUUID().toString() : input.id(), input.siteId(), input.templateId(), BUILTIN_AGENT, List.of(), now, now);
        return repository.save(WebCardApplicationService.SESSIONS, value.id(), value);
    }
    public Page<AgentSession> sessions(int page, int size) { return new Page<>(repository.page(WebCardApplicationService.SESSIONS, page, size, AgentSession.class), repository.count(WebCardApplicationService.SESSIONS)); }
    public void delete(String sessionId) {
        requireSession(sessionId);
        boolean active = streams.values().stream().anyMatch(value -> value.stream().sessionId().equals(sessionId) && !value.stream().isCompleted());
        if (active) throw new IllegalStateException("会话仍在生成中，请稍后删除");
        streams.entrySet().removeIf(entry -> entry.getValue().stream().sessionId().equals(sessionId));
        while (true) {
            List<AgentProposal> proposals = repository.findBy(WebCardApplicationService.PROPOSALS, "sessionId", sessionId, 1, 200, AgentProposal.class);
            if (proposals.isEmpty()) break;
            proposals.forEach(value -> repository.delete(WebCardApplicationService.PROPOSALS, value.id()));
        }
        repository.delete(WebCardApplicationService.SESSIONS, sessionId);
    }
    public AgentProposal message(String sessionId, String message) {
        AgentSession session = requireSession(sessionId);
        requireMessage(message);
        List<PluginAiChatMessage> history = history(session);
        String assistant = ai.runAgent(BUILTIN_AGENT, humanRequest(message, history)).toCompletableFuture().join().content();
        persistTurn(session, message, assistant);
        return saveProposal(session.id(), compilePlan(session.id(), message, assistant, history));
    }

    public String startMessage(String sessionId, String message) {
        AgentSession session = requireSession(sessionId);
        requireMessage(message);
        pruneStreams();
        if (streams.size() >= 100) throw new IllegalStateException("Agent 流任务过多，请稍后重试");
        String streamId = UUID.randomUUID().toString();
        AgentMessageStream stream = new AgentMessageStream(streamId, sessionId,
                value -> executeStream(session, message, value), workers);
        streams.put(streamId, new ManagedStream(stream, System.currentTimeMillis()));
        return streamId;
    }

    public PluginSseStream stream(String streamId) {
        pruneStreams();
        ManagedStream managed = streams.get(streamId);
        if (managed == null) throw new IllegalArgumentException("Agent 消息流不存在或已过期");
        return managed.stream();
    }

    private void executeStream(AgentSession session, String message, AgentMessageStream stream) {
        stream.start();
        List<PluginAiChatMessage> history = history(session);
        StringBuilder received = new StringBuilder();
        String assistant = ai.runAgentStream(BUILTIN_AGENT, humanRequest(message, history), delta -> {
            if (delta == null || delta.isEmpty()) return;
            received.append(delta);
            stream.delta(delta);
        }).toCompletableFuture().join().content();
        if (assistant == null || assistant.isBlank()) assistant = received.toString();
        persistTurn(session, message, assistant);
        stream.completeMessage(assistant);
        AgentPlanParser.Result result = compilePlanResult(session.id(), message, assistant, history);
        if (result.plan().isPresent()) stream.proposal(saveProposal(session.id(), result.plan().orElseThrow()));
        else stream.warning(result.warning().orElse("Agent 未返回有效的结构化提案"));
        stream.complete();
    }

    private PluginAiChatRequest humanRequest(String message, List<PluginAiChatMessage> history) {
        String prompt = """
                你是网站卡片工作室的内置 Agent。像产品搭档一样用简洁自然语言回答管理员，说明你理解的需求、关键取舍和接下来形成的方案。
                不要输出 JSON、Markdown 代码块、Cookie、Authorization 或 API Key 值。认证信息只能提示管理员稍后在安全表单中配置。
                当前脱敏工作区与可选连接/群：
                """ + workspaceSnapshot() + "\n页面结构分析（如有 URL）：\n" + pageInspection(message);
        return new PluginAiChatRequest(prompt, message, null, null, history, null, false);
    }

    private PluginAiChatRequest planRequest(String sessionId, String message, String assistant, List<PluginAiChatMessage> history) {
        List<PluginAiChatMessage> context = new ArrayList<>(history);
        context.add(new PluginAiChatMessage("user", message));
        context.add(new PluginAiChatMessage("assistant", assistant));
        String prompt = """
                你是网站卡片工作室的方案编译器。根据对话生成完整、可审阅的 WorkspacePlan。
                只输出一个 JSON 对象；不要解释、使用 Markdown 或只给变更说明。
                {"summary":"方案摘要","site":{"id":"可空","name":"名称","enabled":true,"hosts":["example.com"],"accessMode":"PUBLIC_HTTP|CUSTOM_HEADERS","responseType":"HTML|JSON","redirectHosts":[]},"rules":{"detailType":"HTML|JSON","detailUrlPattern":"例如 /class/{id}.html","fields":[{"name":"title","expression":"CSS Selector 或 JSONPath","attribute":"text","type":"TEXT","required":true},{"name":"image","expression":"meta[property=og:image]","attribute":"content","type":"URL","required":false},{"name":"summary","expression":"meta[name=description]","attribute":"content","type":"TEXT","required":false}],"listExpression":"","listLinkAttribute":"href","jsonItemsPath":"","canonicalField":"url","contentKeyField":"url"},"template":{"id":"可空","name":"模板名","mode":"STRUCTURED|ADVANCED","structuredLayout":"default","html":"","css":"","fixture":{}},"binding":null,"job":null,"publish":false}
                根据现有工作区和当前待修订方案做增量修改而不是重复创建；域名使用精确 host；优先结构化模板。即时链接回复原群，不使用 binding；binding 仅在管理员明确要求定时任务主动推送时才允许生成，否则必须为 null。
                只有管理员明确要求采集、抓取、定时、RSS 或 Sitemap 时才生成 job，否则必须为 null。绝不索取、回显或生成 Cookie、Authorization、API Key 值，需要鉴权时只设置 accessMode=CUSTOM_HEADERS。
                管理员明确说“只需要渲染规则”“不需要投递”时，binding、job 必须为 null，publish 必须为 false。不得虚构 Modrinth、CurseForge、actions、按钮或插件不支持的数组映射能力。页面资料中出现真实图片时必须配置 image 字段并在模板中展示。
                图片 URL 域名与页面主域名不同时，必须把图片的精确域名加入 site.redirectHosts；渲染管线会下载图片、校验类型和大小，并转成 Base64 Data URL，不要把 Base64 内容写进提案。
                字段规则 type 可使用 TEXT、URL、TEXT_LIST、KEY_VALUE_LIST、LINK_LIST、TABLE。嵌套 ul/ol 优先用 TEXT_LIST、KEY_VALUE_LIST 或 LINK_LIST，真实 table 用 TABLE，不要把整块 DOM 压成一段文本。
                优先选择对用户有决策价值的字段，例如平台、运作方式、运行环境、支持版本、作者、标签和真实相关链接；收录时间、编辑次数、最后推荐等运营噪声默认不展示，除非管理员明确要求。
                structuredLayout 必须是 JSON 字符串并根据实际字段生成 sections 分区，不得只使用固定 title/summary。例如：{"variant":"editorial","accentColor":"#39725d","showImage":true,"showSource":true,"showSummary":true,"showUrl":true,"extraFields":"auto","sections":[{"title":"核心信息","layout":"grid","fields":["基础信息"]},{"title":"支持版本","layout":"chips","fields":["支持版本"]},{"title":"作者与链接","layout":"links","fields":["作者","相关链接"]}]}。sections 中的字段名必须与 rules.fields.name 完全一致。
                当前脱敏工作区与可选连接/群：
                """ + workspaceSnapshot() + "\n页面结构分析（候选选择器、样例值和推荐类型）：\n" + pageInspection(message)
                + "\n当前会话中待修订的方案（如果存在，后续意见应在此方案上修改）：\n" + pendingWorkspace(sessionId);
        return new PluginAiChatRequest(prompt, message, null, null, context, null, false);
    }

    private Map<String, Object> compilePlan(
            String sessionId,
            String message,
            String assistant,
            List<PluginAiChatMessage> history
    ) {
        AgentPlanParser.Result result = compilePlanResult(sessionId, message, assistant, history);
        return result.plan().orElseThrow(() -> new IllegalArgumentException(
                result.warning().orElse("Agent 提案编译失败")
        ));
    }

    private AgentPlanParser.Result compilePlanResult(
            String sessionId,
            String message,
            String assistant,
            List<PluginAiChatMessage> history
    ) {
        boolean crawlIntent = AgentPlanParser.hasExplicitCrawlIntent(message);
        boolean bindingIntent = AgentPlanParser.hasExplicitBindingIntent(message);
        for (int attempt = 0; attempt < 2; attempt++) {
            String repair = attempt == 0 ? ""
                    : "上一轮提案生成失败或未通过结构校验。请重新输出完整 WorkspacePlan，不能只输出说明或局部修改。";
            try {
                AgentPlanParser.Result result = AgentPlanParser.tryParse(
                        compilePlanContent(sessionId, message, assistant, history, repair),
                        crawlIntent,
                        bindingIntent
                );
                if (result.plan().isPresent()) {
                    return result;
                }
            } catch (RuntimeException ignored) {
                // A provider or structured node failure is retried once with a repair instruction.
            }
        }
        return new AgentPlanParser.Result(
                java.util.Optional.empty(),
                java.util.Optional.of("结构化提案生成失败，系统已自动重试。请检查当前模型是否支持 JSON 结构化输出。")
        );
    }

    private String compilePlanContent(
            String sessionId,
            String message,
            String assistant,
            List<PluginAiChatMessage> history,
            String repairInstruction
    ) {
        PluginAiChatRequest request = planRequest(sessionId, message, assistant, history);
        if (repairInstruction != null && !repairInstruction.isBlank()) {
            request = new PluginAiChatRequest(
                    request.systemPrompt() + "\n" + repairInstruction,
                    request.userPrompt(),
                    request.providerCode(),
                    request.modelCode(),
                    request.history(),
                    request.executionContext(),
                    request.toolCallingEnabled()
            );
        }
        return ai.runAgent(PLAN_COMPILER_AGENT, request).toCompletableFuture().join().content();
    }

    private AgentSession requireSession(String id) {
        return repository.find(WebCardApplicationService.SESSIONS, id, AgentSession.class)
                .orElseThrow(() -> new IllegalArgumentException("Agent 会话不存在"));
    }

    private void requireMessage(String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
    }

    private List<PluginAiChatMessage> history(AgentSession session) {
        return session.messages().stream()
                .map(row -> new PluginAiChatMessage(row.get("role"), row.get("content")))
                .toList();
    }

    private void persistTurn(AgentSession session, String user, String assistant) {
        long now = System.currentTimeMillis();
        List<Map<String, String>> messages = new ArrayList<>(session.messages());
        messages.add(Map.of("role", "user", "content", user));
        messages.add(Map.of("role", "assistant", "content", assistant == null ? "" : assistant));
        if (messages.size() > 20) messages = new ArrayList<>(messages.subList(messages.size() - 20, messages.size()));
        repository.save(WebCardApplicationService.SESSIONS, session.id(), new AgentSession(
                session.id(), session.siteId(), session.templateId(), session.agentCode(), messages,
                session.createdAt(), now));
    }

    private AgentProposal saveProposal(String sessionId, Map<String, Object> plan) {
        List<PatchOperation> operations = List.of(new PatchOperation("workspace", "replace", plan));
        long now = System.currentTimeMillis();
        AgentProposal proposal = new AgentProposal(UUID.randomUUID().toString(), sessionId,
                String.valueOf(plan.getOrDefault("summary", "Agent 工作区方案")), operations,
                ProposalStatus.PENDING, null, now, now);
        AgentProposal saved = repository.save(WebCardApplicationService.PROPOSALS, proposal.id(), proposal);
        repository.findBy(WebCardApplicationService.PROPOSALS, "sessionId", sessionId, 1, 200, AgentProposal.class).stream()
                .filter(value -> !value.id().equals(saved.id()) && value.status() == ProposalStatus.PENDING)
                .forEach(value -> repository.save(WebCardApplicationService.PROPOSALS, value.id(), new AgentProposal(value.id(), value.sessionId(), value.summary(), value.operations(), ProposalStatus.REJECTED, value.previewVersionId(), value.createdAt(), now)));
        return saved;
    }

    private String pendingWorkspace(String sessionId) {
        return repository.findBy(WebCardApplicationService.PROPOSALS, "sessionId", sessionId, 1, 200, AgentProposal.class).stream()
                .filter(value -> value.status() == ProposalStatus.PENDING).max(java.util.Comparator.comparingLong(AgentProposal::updatedAt))
                .map(value -> { try { return JsonSupport.MAPPER.writeValueAsString(value.operations().stream().filter(operation -> "workspace".equals(operation.target())).findFirst().map(PatchOperation::value).orElse(Map.of())); } catch (Exception ignored) { return "{}"; } }).orElse("{}");
    }

    private String pageInspection(String message) {
        if (message == null) return "{}";
        var matcher = URL.matcher(message);
        if (!matcher.find()) return "{}";
        try {
            return JsonSupport.MAPPER.writeValueAsString(app.inspectForAgent(matcher.group()));
        } catch (Exception error) {
            return "{\"warning\":\"页面结构分析失败，必须依据管理员提供的 DOM，不能虚构选择器\"}";
        }
    }

    private void pruneStreams() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        streams.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff && entry.getValue().stream().isCompleted());
        if (streams.size() < 100) return;
        streams.entrySet().stream()
                .filter(entry -> entry.getValue().stream().isCompleted())
                .sorted(Map.Entry.comparingByValue((left, right) -> Long.compare(left.createdAt(), right.createdAt())))
                .limit(streams.size() - 99L)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(streams::remove);
    }

    public Page<AgentProposal> proposals(int page, int size) { return new Page<>(repository.page(WebCardApplicationService.PROPOSALS, page, size, AgentProposal.class), repository.count(WebCardApplicationService.PROPOSALS)); }
    public AgentProposal update(String id, Map<String, Object> input) {
        AgentProposal current = requirePending(id);
        boolean explicitJob = input != null && input.get("job") instanceof Map<?, ?>;
        boolean explicitBinding = input != null && input.get("binding") instanceof Map<?, ?>;
        Map<String, Object> plan = AgentPlanParser.normalize(input, explicitJob, explicitBinding);
        AgentProposal updated = new AgentProposal(current.id(), current.sessionId(),
                String.valueOf(plan.getOrDefault("summary", current.summary())),
                List.of(new PatchOperation("workspace", "replace", plan)), current.status(),
                current.previewVersionId(), current.createdAt(), System.currentTimeMillis());
        return repository.save(WebCardApplicationService.PROPOSALS, id, updated);
    }
    public TemplateVersion apply(String proposalId) {
        AgentProposal proposal = requirePending(proposalId); AgentSession session = repository.find(WebCardApplicationService.SESSIONS, proposal.sessionId(), AgentSession.class).orElseThrow();
        Map<String,Object> plan = JsonSupport.MAPPER.convertValue(proposal.operations().getFirst().value(), new TypeReference<>() { });
        TemplateVersion saved = applyWorkspace(plan, proposal.summary());
        long now = System.currentTimeMillis(); repository.save(WebCardApplicationService.PROPOSALS, proposal.id(), new AgentProposal(proposal.id(), proposal.sessionId(), proposal.summary(), proposal.operations(), ProposalStatus.APPLIED, saved.id(), proposal.createdAt(), now)); return saved;
    }
    public AgentProposal reject(String id) { AgentProposal proposal = requirePending(id); AgentProposal rejected = new AgentProposal(proposal.id(), proposal.sessionId(), proposal.summary(), proposal.operations(), ProposalStatus.REJECTED, null, proposal.createdAt(), System.currentTimeMillis()); return repository.save(WebCardApplicationService.PROPOSALS, id, rejected); }
    private AgentProposal requirePending(String id) { AgentProposal value = repository.find(WebCardApplicationService.PROPOSALS, id, AgentProposal.class).orElseThrow(() -> new IllegalArgumentException("提案不存在")); if (value.status() != ProposalStatus.PENDING) throw new IllegalArgumentException("提案已处理"); return value; }
    private String workspaceSnapshot() {
        try {
            List<Map<String, Object>> sites = app.sites(1, 100).records().stream().map(value -> Map.<String, Object>of(
                    "id", value.id(), "name", value.name(), "enabled", value.enabled(), "hosts", value.hosts(),
                    "accessMode", value.accessMode(), "responseType", value.responseType(),
                    "redirectHosts", value.redirectHosts(), "headerNames", value.headerNames())).toList();
            return JsonSupport.MAPPER.writeValueAsString(Map.of("sites", sites,
                    "templates", app.templates(1, 100).records(), "bindings", app.bindings(1, 100).records(),
                    "jobs", app.jobs(1, 100).records(), "connections", framework.messaging().connections(),
                    "groups", framework.messaging().connections().stream().collect(java.util.stream.Collectors.toMap(
                            value -> value.id(), value -> framework.messaging().groups(value.id())))));
        } catch (Exception error) {
            throw new IllegalArgumentException("工作区序列化失败", error);
        }
    }
    private TemplateVersion applyWorkspace(Map<String,Object> plan, String summary) {
        long now=System.currentTimeMillis(); Map<String,Object> siteMap=map(plan,"site"); String host=String.valueOf(((List<?>)siteMap.get("hosts")).getFirst());
        Site existing=app.sites(1,100).records().stream().filter(value->value.matches(host)).findFirst().orElse(null); String siteId=text(siteMap.get("id"),existing==null?UUID.randomUUID().toString():existing.id());
        List<String> hosts=JsonSupport.MAPPER.convertValue(siteMap.get("hosts"),new TypeReference<>(){}); List<String> redirects=JsonSupport.MAPPER.convertValue(siteMap.getOrDefault("redirectHosts",List.of()),new TypeReference<>(){});
        Site site=app.saveSite(new Site(siteId,text(siteMap.get("name"),host),bool(siteMap.get("enabled"),true),hosts,AccessMode.valueOf(text(siteMap.get("accessMode"),"PUBLIC_HTTP")),existing==null?List.of():existing.headerNames(),existing==null?null:existing.secretRef(),SourceType.valueOf(text(siteMap.get("responseType"),"HTML")),redirects,existing==null?null:existing.defaultTemplateId(),existing==null?now:existing.createdAt(),now),null);
        ParseRules rules=JsonSupport.MAPPER.convertValue(map(plan,"rules"),ParseRules.class); rules=app.saveRules(new ParseRules(siteId,rules.detailType(),rules.fields(),rules.listExpression(),rules.listLinkAttribute(),rules.jsonItemsPath(),rules.canonicalField(),rules.contentKeyField(),rules.detailUrlPattern()));
        Map<String,Object> templateMap=map(plan,"template"); Template existingTemplate=app.templates(1,100).records().stream().filter(value->value.siteId().equals(siteId)).findFirst().orElse(null); String templateId=text(templateMap.get("id"),existingTemplate==null?UUID.randomUUID().toString():existingTemplate.id());
        TemplateMode mode=TemplateMode.valueOf(text(templateMap.get("mode"),"STRUCTURED")); Template template=app.saveTemplate(new Template(templateId,siteId,text(templateMap.get("name"),"网站卡片"),mode,existingTemplate==null?null:existingTemplate.draftVersionId(),existingTemplate==null?null:existingTemplate.publishedVersionId(),existingTemplate==null?now:existingTemplate.createdAt(),now));
        TemplateVersion version=app.saveVersion(new TemplateVersion(null,template.id(),0,rules,mode,text(templateMap.get("structuredLayout"),"default"),text(templateMap.get("html"),""),text(templateMap.get("css"),""),JsonSupport.MAPPER.convertValue(templateMap.getOrDefault("fixture",Map.of()),new TypeReference<>(){}),"AGENT",summary,false,now));
        if(plan.get("binding") instanceof Map<?,?> raw){Map<String,Object> bindingMap=JsonSupport.MAPPER.convertValue(raw,new TypeReference<>(){});String connection=text(bindingMap.get("connectionId"),"");String channel=text(bindingMap.get("channelId"),"");if(!connection.isBlank()&&!channel.isBlank()){var connectionOption=framework.messaging().connections().stream().filter(value->value.id().equals(connection)).findFirst().orElseThrow();GroupBinding old=app.bindings(1,100).records().stream().filter(value->value.siteId().equals(siteId)&&value.connectionId().equals(connection)&&value.channelId().equals(channel)).findFirst().orElse(null);app.saveBinding(new GroupBinding(old==null?UUID.randomUUID().toString():old.id(),siteId,connection,connectionOption.platform(),connectionOption.userId(),channel,bool(bindingMap.get("enabled"),true),version.id(),text(bindingMap.get("quietStart"),""),text(bindingMap.get("quietEnd"),""),number(bindingMap.get("cooldownSeconds"),0),number(bindingMap.get("hourlyLimit"),0),old==null?0:old.lastDeliveryAt(),old==null?now:old.createdAt(),now));}}
        if(plan.get("job") instanceof Map<?,?> raw){Map<String,Object> job=JsonSupport.MAPPER.convertValue(raw,new TypeReference<>(){});String source=text(job.get("sourceUrl"),"");if(!source.isBlank()){CrawlJob old=app.jobs(1,100).records().stream().filter(value->value.siteId().equals(siteId)&&value.sourceUrl().equals(source)).findFirst().orElse(null);app.saveJob(new CrawlJob(old==null?UUID.randomUUID().toString():old.id(),siteId,source,SourceType.valueOf(text(job.get("sourceType"),"RSS")),bool(job.get("enabled"),true),number(job.get("intervalMinutes"),30),number(job.get("initialItemCount"),3),old==null?now:old.nextRunAt(),null,0,old!=null&&old.initialized(),old==null?now:old.createdAt(),now));}}
        if(bool(plan.get("publish"),false)){Map<String,Object> preview=app.preview(version.id(),version.fixture());TemplateVersion prepared=(TemplateVersion)preview.get("version");app.publish(template.id(),prepared.id());return prepared;}
        return version;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Map<String,Object> value,String key){return JsonSupport.MAPPER.convertValue(value.get(key),new TypeReference<>(){});} private String text(Object value,String fallback){return value==null||String.valueOf(value).isBlank()?fallback:String.valueOf(value);} private int number(Object value,int fallback){try{return Integer.parseInt(String.valueOf(value));}catch(Exception e){return fallback;}} private boolean bool(Object value,boolean fallback){return value==null?fallback:Boolean.parseBoolean(String.valueOf(value));}
    @Override public void close() { streams.clear(); workers.close(); }
    private record ManagedStream(AgentMessageStream stream, long createdAt) { }
}
