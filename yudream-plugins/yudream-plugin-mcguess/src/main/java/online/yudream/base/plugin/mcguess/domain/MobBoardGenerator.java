package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 猜生物棋盘生成器：随机抽取 3 行条件 + 3 列条件，
 * 要求 9 个交叉格每格至少 2 个候选，且整盘存在不重复填法（二分图完美匹配）。
 */
public class MobBoardGenerator {

    /** 单格最少候选数。 */
    static final int MIN_CELL_CANDIDATES = 2;
    /** 最大重试次数。 */
    static final int MAX_ATTEMPTS = 500;
    /** 每组行条件下尝试的列组合次数。 */
    static final int MAX_COL_ATTEMPTS = 20;

    private final McMobCatalog catalog;

    public MobBoardGenerator(McMobCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 两段式生成：先抽 3 个行条件，再只在「与全部行条件交叉候选 ≥2」的条件中抽 3 个列条件，
     * 最后以完美匹配校验整盘可解。随机抽 6 个条件的朴素做法在条件互斥（友好/敌对、维度）下几乎必然失败。
     */
    public MobBoard generate(Random random) {
        List<McMobCatalog.McCondition> conditions = catalog.conditions();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<McMobCatalog.McCondition> shuffled = new ArrayList<>(conditions);
            Collections.shuffle(shuffled, random);
            List<String> rows = shuffled.subList(0, 3).stream().map(McMobCatalog.McCondition::code).toList();
            List<String> colCandidates = new ArrayList<>();
            for (McMobCatalog.McCondition condition : conditions) {
                String code = condition.code();
                if (rows.contains(code)) {
                    continue;
                }
                boolean compatible = rows.stream()
                        .allMatch(row -> catalog.intersection(row, code).size() >= MIN_CELL_CANDIDATES);
                if (compatible) {
                    colCandidates.add(code);
                }
            }
            if (colCandidates.size() < 3) {
                continue;
            }
            for (int colAttempt = 0; colAttempt < MAX_COL_ATTEMPTS; colAttempt++) {
                Collections.shuffle(colCandidates, random);
                List<String> cols = List.copyOf(colCandidates.subList(0, 3));
                List<List<String>> cells = new ArrayList<>(9);
                for (String row : rows) {
                    for (String col : cols) {
                        cells.add(catalog.intersection(row, col).stream()
                                .map(McMobCatalog.McMob::id)
                                .toList());
                    }
                }
                List<String> solution = perfectMatching(cells);
                if (solution != null) {
                    return new MobBoard(rows, cols, solution);
                }
            }
        }
        throw new IllegalStateException("无法生成可解的猜生物棋盘，请检查生物数据集");
    }

    /**
     * 二分匹配：每个格子一组候选生物，求不重复填法。
     * 返回与格子等长的可行解（按格子顺序的生物 id），无解返回 null。
     */
    static List<String> perfectMatching(List<List<String>> cells) {
        Map<String, Integer> mobToCell = new HashMap<>();
        List<String> solution = new ArrayList<>(Collections.nCopies(cells.size(), null));
        for (int cell = 0; cell < cells.size(); cell++) {
            if (!augment(cell, cells, mobToCell, solution, new HashSet<>())) {
                return null;
            }
        }
        return solution;
    }

    private static boolean augment(int cell, List<List<String>> cells, Map<String, Integer> mobToCell,
                                   List<String> solution, Set<Integer> visited) {
        if (!visited.add(cell)) {
            return false;
        }
        for (String mob : cells.get(cell)) {
            Integer occupied = mobToCell.get(mob);
            if (occupied == null) {
                mobToCell.put(mob, cell);
                solution.set(cell, mob);
                return true;
            }
            if (augment(occupied, cells, mobToCell, solution, visited)) {
                mobToCell.put(mob, cell);
                solution.set(cell, mob);
                return true;
            }
        }
        return false;
    }

    /**
     * 一张可解的猜生物棋盘。
     *
     * @param rows     3 个行条件 code
     * @param cols     3 个列条件 code
     * @param solution 一组可行答案（9 格按行优先顺序的生物 id），失败揭晓时展示
     */
    public record MobBoard(List<String> rows, List<String> cols, List<String> solution) {
    }
}
