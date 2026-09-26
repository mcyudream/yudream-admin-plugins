package online.yudream.base.plugin.forum.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ForumModels {
    private ForumModels() {}
    public enum Status { DRAFT, PENDING, PUBLISHED, REJECTED, ARCHIVED }
    public enum Moderation { INHERIT, OFF, MANUAL, AI }

    public record Category(String id, String slug, String name, String description, int sort, boolean enabled,
                           String viewPermission, String postPermission, Moderation moderation,
                           Boolean aiTagging, long createdAt, long updatedAt) {
        public Map<String,Object> doc() {
            Map<String,Object> d = new HashMap<>();
            d.put("id", id); d.put("slug", nameOr(slug)); d.put("name", nameOr(name)); d.put("description", nameOr(description));
            d.put("sort", sort); d.put("enabled", enabled); d.put("viewPermission", nameOr(viewPermission)); d.put("postPermission", nameOr(postPermission));
            d.put("moderation", (moderation == null ? Moderation.INHERIT : moderation).name()); d.put("aiTagging", Boolean.TRUE.equals(aiTagging));
            d.put("createdAt", createdAt); d.put("updatedAt", updatedAt); return d;
        }
        private static String nameOr(String value) { return value == null ? "" : value; }
    }
    public record Post(String id, String title, String body, String summary, String categoryId, List<String> tags,
                       String authorId, Status status, String rejectionReason, boolean pinned, boolean featured,
                       long publishedAt, long createdAt, long updatedAt, long views, long likes, long comments, long bookmarks) {
        public Map<String,Object> doc() {
            Map<String,Object> d = new HashMap<>(); d.put("id", id); d.put("title", text(title)); d.put("body", text(body)); d.put("summary", text(summary));
            d.put("categoryId", text(categoryId)); d.put("tags", tags == null ? List.of() : List.copyOf(tags)); d.put("authorId", text(authorId));
            d.put("status", (status == null ? Status.DRAFT : status).name()); d.put("rejectionReason", text(rejectionReason)); d.put("pinned", pinned); d.put("featured", featured);
            d.put("publishedAt", publishedAt); d.put("createdAt", createdAt); d.put("updatedAt", updatedAt); d.put("views", views); d.put("likes", likes); d.put("comments", comments); d.put("bookmarks", bookmarks); return d;
        }
        private static String text(String value) { return value == null ? "" : value; }
    }
    public record Comment(String id, String postId, String authorId, String body, String parentId, Status status, long createdAt) {
        public Map<String,Object> doc() {
            Map<String,Object> d = new HashMap<>(); d.put("id", id); d.put("postId", postId); d.put("authorId", authorId); d.put("body", body == null ? "" : body); d.put("parentId", parentId == null ? "" : parentId); d.put("status", (status == null ? Status.PUBLISHED : status).name()); d.put("createdAt", createdAt); return d;
        }
    }
    public static Moderation moderation(String raw) { try { return Moderation.valueOf(raw); } catch (Exception e) { return Moderation.INHERIT; } }
    public static Status status(String raw) { try { return Status.valueOf(raw); } catch (Exception e) { return Status.DRAFT; } }
}
