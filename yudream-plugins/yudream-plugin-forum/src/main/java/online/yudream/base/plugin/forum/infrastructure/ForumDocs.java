package online.yudream.base.plugin.forum.infrastructure;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.forum.domain.ForumModels;

public final class ForumDocs {
    private ForumDocs() {}
    public static ForumModels.Category category(Map<String,Object> d) { return new ForumModels.Category(Doc.text(d,"id"),Doc.text(d,"slug"),Doc.text(d,"name"),Doc.text(d,"description"),Doc.integer(d,"sort"),Doc.bool(d,"enabled"),Doc.text(d,"viewPermission"),Doc.text(d,"postPermission"),ForumModels.moderation(Doc.text(d,"moderation")),Doc.bool(d,"aiTagging"),Doc.number(d,"createdAt"),Doc.number(d,"updatedAt")); }
    public static ForumModels.Post post(Map<String,Object> d) { return new ForumModels.Post(Doc.text(d,"id"),Doc.text(d,"title"),Doc.text(d,"body"),Doc.text(d,"summary"),Doc.text(d,"categoryId"),Doc.strings(d,"tags"),Doc.text(d,"authorId"),ForumModels.status(Doc.text(d,"status")),Doc.text(d,"rejectionReason"),Doc.bool(d,"pinned"),Doc.bool(d,"featured"),Doc.number(d,"publishedAt"),Doc.number(d,"createdAt"),Doc.number(d,"updatedAt"),Doc.number(d,"views"),Doc.number(d,"likes"),Doc.number(d,"comments"),Doc.number(d,"bookmarks")); }
    public static ForumModels.Comment comment(Map<String,Object> d) { return new ForumModels.Comment(Doc.text(d,"id"),Doc.text(d,"postId"),Doc.text(d,"authorId"),Doc.text(d,"body"),Doc.text(d,"parentId"),ForumModels.status(Doc.text(d,"status")),Doc.number(d,"createdAt")); }
}
