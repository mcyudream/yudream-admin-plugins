package online.yudream.plugin.codextasknotify.application.dto;

public record CodexTaskNotification(
        Type type,
        String taskId,
        String title,
        String message,
        String taskUrl
) {

    public enum Type {
        COMPLETED("任务已完成"),
        ACTION_REQUIRED("需要你的确认"),
        INTERRUPTED("任务已中断");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
