package online.yudream.base.plugin.forum.application;

public record PageResult<T>(java.util.List<T> records, long total) {}
