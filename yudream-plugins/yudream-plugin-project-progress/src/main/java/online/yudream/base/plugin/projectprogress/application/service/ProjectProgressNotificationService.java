package online.yudream.base.plugin.projectprogress.application.service;

import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressProject;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectWorkDetail;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.mail.PluginMailMessage;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

import java.util.List;

public class ProjectProgressNotificationService {
    private final FrameworkServices framework;

    public ProjectProgressNotificationService(FrameworkServices framework) {
        this.framework = framework;
    }

    public void notifyAssigned(ProjectProgressProject project, ProjectWorkDetail detail, List<String> userIds) {
        send(userIds, "Project task assigned", "You have a new task: " + project.name() + " / " + detail.title());
    }

    public void notifyRework(ProjectProgressProject project, ProjectWorkDetail detail, List<String> userIds, String reason) {
        send(userIds, "Project task returned", "Please rework: " + project.name() + " / " + detail.title()
                + (reason == null || reason.isBlank() ? "" : "\nReason: " + reason));
    }

    public void notifyCheckInReminder(ProjectProgressProject project, List<String> userIds) {
        send(userIds, "Project check-in reminder", "Please complete this check-in period: " + project.name());
    }

    public void notifyPublished(ProjectProgressProject project, ProjectWorkDetail detail) {
        if (framework == null || framework.messaging() == null || project.notificationConnectionId() == null
                || project.notificationConnectionId().isBlank()
                || project.notificationChannelId().isBlank()) return;
        // 官方 QQ 机器人连接发 markdown 卡片；Milky 等其他协议保持原纯文本，协议行为不变
        PluginMessageContent content = officialConnection(project.notificationConnectionId())
                ? new PluginMessageContent(PluginMessageContent.Type.MARKDOWN, publishedMarkdown(project, detail), null, null)
                : new PluginMessageContent(PluginMessageContent.Type.TEXT,
                        "Project detail published\n" + project.name() + " / " + detail.title(), null, null);
        framework.messaging().sendToChannel(project.notificationConnectionId(), project.notificationChannelId(), content)
                .exceptionally(ignored -> null);
    }

    private boolean officialConnection(String connectionId) {
        try {
            return framework.messaging().connections().stream()
                    .filter(connection -> connection.id().equals(connectionId))
                    .map(PluginMessagingConnection::protocol)
                    .anyMatch("official"::equals);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String publishedMarkdown(ProjectProgressProject project, ProjectWorkDetail detail) {
        StringBuilder markdown = new StringBuilder("# 📣 项目细节发布\n\n**")
                .append(project.name()).append("**\n\n## ").append(detail.title());
        if (detail.description() != null && !detail.description().isBlank()) {
            markdown.append("\n\n").append(detail.description().trim());
        }
        if (detail.dueAt() != null && detail.dueAt() > 0) {
            markdown.append("\n\n> ⏰ 截止时间：").append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(detail.dueAt())));
        }
        return markdown.toString();
    }

    private void send(List<String> userIds, String subject, String text) {
        if (framework == null || userIds == null || userIds.isEmpty()) return;
        for (String userId : userIds) {
            if (framework.messaging() != null) {
                framework.messaging().sendDirectToBoundUser(userId,
                        new PluginMessageContent(PluginMessageContent.Type.TEXT, subject + "\n" + text, null, null))
                        .exceptionally(ignored -> null);
            }
            if (framework.mail() != null) {
                email(userId).ifPresent(email -> framework.mail().send(PluginMailMessage.text(List.of(email), subject, text)));
            }
        }
    }

    private java.util.Optional<String> email(String userId) {
        if (framework.users() == null || userId == null || userId.isBlank()) return java.util.Optional.empty();
        try {
            return framework.users().findById(Long.parseLong(userId.trim())).map(PluginUserProfile::email)
                    .filter(value -> value != null && !value.isBlank());
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }
}
