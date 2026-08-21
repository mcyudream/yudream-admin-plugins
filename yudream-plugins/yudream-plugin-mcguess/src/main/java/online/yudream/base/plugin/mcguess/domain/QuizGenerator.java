package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 快答出题器：每题为「合成 1 个 X 总共需要几个 Y」，
 * 答案取自目标合成树中 Y 的总出现次数；四个互不相同的选项含答案（已乱序）。
 */
public class QuizGenerator {

    /** 出题重试上限（目标没有合适带图标原料时换目标）。 */
    private static final int MAX_ATTEMPTS = 200;

    private final McCatalog catalog;

    public QuizGenerator(McCatalog catalog) {
        this.catalog = catalog;
    }

    public List<QuizGame.Question> generate(Random random) {
        List<QuizGame.Question> questions = new ArrayList<>();
        Set<String> usedTargets = new LinkedHashSet<>();
        int attempts = 0;
        while (questions.size() < QuizGame.QUESTION_COUNT && attempts++ < MAX_ATTEMPTS) {
            McItem target = catalog.randomTarget(random);
            if (!usedTargets.add(target.id())) {
                continue;
            }
            QuizGame.Question question = buildQuestion(target, random);
            if (question != null) {
                questions.add(question);
            }
        }
        if (questions.size() < QuizGame.QUESTION_COUNT) {
            throw new IllegalStateException("快答出题失败：可用题目不足");
        }
        return questions;
    }

    private QuizGame.Question buildQuestion(McItem target, Random random) {
        McCatalog.TreeInfo tree = catalog.treeOf(target.id());
        List<String> ingredients = tree.occurrences().keySet().stream()
                .filter(id -> !id.equals(target.id()))
                .filter(id -> catalog.byId(id).map(McItem::icon).orElse(false))
                .toList();
        if (ingredients.isEmpty()) {
            return null;
        }
        String ingredient = ingredients.get(random.nextInt(ingredients.size()));
        int answer = tree.occurrencesOf(ingredient);
        if (answer <= 0) {
            return null;
        }
        return new QuizGame.Question(target.id(), ingredient, answer, buildChoices(answer, random));
    }

    /** 四个互不相同的正整数选项，含答案且乱序。 */
    private List<Integer> buildChoices(int answer, Random random) {
        Set<Integer> pool = new LinkedHashSet<>(
                List.of(answer - 2, answer - 1, answer + 1, answer + 2, answer * 2, answer + 3, answer + 4, answer * 3));
        pool.removeIf(value -> value <= 0 || value == answer);
        List<Integer> distractors = new ArrayList<>(pool);
        while (distractors.size() < QuizGame.CHOICE_COUNT - 1) {
            int candidate = answer + distractors.size() + 4;
            if (candidate != answer && !distractors.contains(candidate)) {
                distractors.add(candidate);
            }
        }
        java.util.Collections.shuffle(distractors, random);
        Set<Integer> choices = new LinkedHashSet<>(distractors.subList(0, QuizGame.CHOICE_COUNT - 1));
        choices.add(answer);
        List<Integer> result = new ArrayList<>(choices);
        java.util.Collections.shuffle(result, random);
        return result;
    }
}
