package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 生物目录：生物查询、中文名匹配、按条件索引候选。
 * 数据集为手工精选的 classpath 资源 mcguess/mcmobs.json。
 */
public class McMobCatalog {

    private final List<McMob> mobs;
    private final Map<String, McMob> byId;
    private final Map<String, McMob> byZh;
    private final List<McCondition> conditions;
    private final Map<String, McCondition> conditionByCode;
    private final Map<String, List<McMob>> candidatesByCondition;

    public McMobCatalog(List<McMob> mobs, List<McCondition> conditions) {
        this.mobs = List.copyOf(mobs);
        this.conditions = List.copyOf(conditions);
        this.byId = new HashMap<>();
        this.byZh = new HashMap<>();
        for (McMob mob : mobs) {
            byId.put(mob.id(), mob);
            byZh.putIfAbsent(mob.zh(), mob);
        }
        this.conditionByCode = new LinkedHashMap<>();
        for (McCondition condition : conditions) {
            conditionByCode.put(condition.code(), condition);
        }
        this.candidatesByCondition = new HashMap<>();
        for (McCondition condition : conditions) {
            List<McMob> candidates = new ArrayList<>();
            for (McMob mob : mobs) {
                if (mob.cond().contains(condition.code())) {
                    candidates.add(mob);
                }
            }
            candidatesByCondition.put(condition.code(), List.copyOf(candidates));
        }
    }

    public List<McMob> mobs() {
        return mobs;
    }

    public List<McCondition> conditions() {
        return conditions;
    }

    public Optional<McMob> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<McCondition> condition(String code) {
        return Optional.ofNullable(conditionByCode.get(code));
    }

    /** 按中文名匹配生物：精确匹配全名，空输入或不存在返回 empty。 */
    public Optional<McMob> match(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim().replaceAll("[\\s·]", "");
        if (input.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byZh.get(input));
    }

    /** 满足某条件的全部生物。 */
    public List<McMob> candidates(String conditionCode) {
        return candidatesByCondition.getOrDefault(conditionCode, List.of());
    }

    /** 同时满足行 + 列条件的候选。 */
    public List<McMob> intersection(String rowCondition, String colCondition) {
        List<McMob> result = new ArrayList<>();
        for (McMob mob : candidates(rowCondition)) {
            if (mob.cond().contains(colCondition)) {
                result.add(mob);
            }
        }
        return result;
    }

    /** 生物的刷怪蛋图标资源 id（与 mcguess/icons/<egg>.png 对应）。 */
    public String eggIconId(String mobId) {
        return mobId + "_spawn_egg";
    }

    /**
     * 一种生物及其满足的条件 code 列表。
     */
    public record McMob(String id, String zh, List<String> cond) {
    }

    /**
     * 一个棋盘条件。
     */
    public record McCondition(String code, String zh) {
    }
}
