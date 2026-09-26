package online.yudream.base.plugin.forum.infrastructure;

import java.util.List;
import java.util.Map;

public final class Doc {
    private Doc() {}
    public static String text(Map<String,Object> d, String k) { Object v=d.get(k); return v == null ? "" : String.valueOf(v); }
    public static long number(Map<String,Object> d, String k) { try { return Long.parseLong(text(d,k)); } catch (Exception e) { return 0; } }
    public static int integer(Map<String,Object> d, String k) { return (int) number(d,k); }
    public static boolean bool(Map<String,Object> d, String k) { return Boolean.parseBoolean(text(d,k)); }
    public static List<String> strings(Map<String,Object> d, String k) { Object v=d.get(k); return v instanceof List<?> l ? l.stream().filter(x -> x != null).map(String::valueOf).toList() : List.of(); }
}
