package online.yudream.base.plugin.activityproof.domain.valobj;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 高级自定义计分的四则运算式：支持 + - * / ( )、十进制数字与单字母参数变量（a-z），
 * 变量取值由核验时逐人填充。语法错误、未知变量在 {@link #parse} 时即报错，把问题挡在保存阶段。
 *
 * <p>刻意不引入脚本引擎：表达式来自管理端自由输入，白名单递归下降解析器保证只做四则运算，
 * 不存在方法调用、字段访问等逃生通道。
 */
public final class ScoreFormula {

    /** 变量名集合为单字母，公式本身不需要太长。 */
    public static final int MAX_LENGTH = 300;

    private static final Set<String> NO_VARIABLES = Set.of();

    private final String expression;
    private final Node root;
    private final Set<String> variables;

    private ScoreFormula(String expression, Node root, Set<String> variables) {
        this.expression = expression;
        this.root = root;
        this.variables = variables;
    }

    /**
     * 解析并校验表达式；{@code allowedVariables} 为空集合表示不允许出现任何变量。
     *
     * @throws IllegalArgumentException 表达式为空、过长、含非法字符、语法错误或引用未定义变量
     */
    public static ScoreFormula parse(String raw, Set<String> allowedVariables) {
        String expression = raw == null ? "" : raw.trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("请填写计分公式");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("计分公式过长，最多 " + MAX_LENGTH + " 个字符");
        }
        Parser parser = new Parser(expression, allowedVariables == null ? NO_VARIABLES : allowedVariables);
        Node root = parser.parseExpression();
        parser.expectEnd();
        return new ScoreFormula(expression, root, parser.usedVariables());
    }

    /** 用每名参与者的参数取值求值；除数为 0 时抛 {@link ArithmeticException}。 */
    public double evaluate(Map<String, Double> values) {
        return root.eval(values);
    }

    /** 表达式实际引用到的变量名。 */
    public Set<String> variables() {
        return variables;
    }

    public String expression() {
        return expression;
    }

    private interface Node {
        double eval(Map<String, Double> values);
    }

    private record Constant(double value) implements Node {
        @Override
        public double eval(Map<String, Double> values) {
            return value;
        }
    }

    private record Variable(String name) implements Node {
        @Override
        public double eval(Map<String, Double> values) {
            Double value = values.get(name);
            // 核验方按参数表填值，缺省 0 只是防御；不应走到这里。
            return value == null ? 0.0 : value;
        }
    }

    private record Negate(Node node) implements Node {
        @Override
        public double eval(Map<String, Double> values) {
            return -node.eval(values);
        }
    }

    private record Binary(char op, Node left, Node right) implements Node {
        @Override
        public double eval(Map<String, Double> values) {
            double leftValue = left.eval(values);
            double rightValue = right.eval(values);
            return switch (op) {
                case '+' -> leftValue + rightValue;
                case '-' -> leftValue - rightValue;
                case '*' -> leftValue * rightValue;
                default -> {
                    if (rightValue == 0.0) {
                        throw new ArithmeticException("计分公式出现除以 0");
                    }
                    yield leftValue / rightValue;
                }
            };
        }
    }

    /** 递归下降解析器：expression := term (('+'|'-') term)*；term := factor (('*'|'/') factor)*。 */
    private static final class Parser {
        private final String text;
        private final Set<String> allowedVariables;
        private final Set<String> usedVariables = new LinkedHashSet<>();
        private int position;

        private Parser(String text, Set<String> allowedVariables) {
            this.text = text;
            this.allowedVariables = allowedVariables;
        }

        private Node parseExpression() {
            Node node = parseTerm();
            while (true) {
                char c = peek();
                if (c == '+' || c == '-') {
                    position++;
                    node = new Binary(c, node, parseTerm());
                } else {
                    return node;
                }
            }
        }

        private Node parseTerm() {
            Node node = parseFactor();
            while (true) {
                char c = peek();
                if (c == '*' || c == '/') {
                    position++;
                    node = new Binary(c, node, parseFactor());
                } else {
                    return node;
                }
            }
        }

        private Node parseFactor() {
            char c = peek();
            if (c == '+') {
                position++;
                return parseFactor();
            }
            if (c == '-') {
                position++;
                return new Negate(parseFactor());
            }
            if (c == '(') {
                position++;
                Node node = parseExpression();
                if (peek() != ')') {
                    throw new IllegalArgumentException("计分公式缺少右括号 )");
                }
                position++;
                return node;
            }
            // 变量只认 ASCII 单字母；中文等字符明确报不支持，避免「服」被当成变量名产生迷惑错误
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                String name = String.valueOf(Character.toLowerCase(c));
                position++;
                if (!allowedVariables.contains(name)) {
                    throw new IllegalArgumentException("计分公式引用了未定义的参数：" + name
                            + (allowedVariables.isEmpty()
                                    ? "（请先添加计分参数）"
                                    : "（可用参数：" + String.join("、", allowedVariables) + "）"));
                }
                usedVariables.add(name);
                return new Variable(name);
            }
            if (Character.isDigit(c) || c == '.') {
                int start = position;
                while (position < text.length()
                        && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) {
                    position++;
                }
                String number = text.substring(start, position);
                try {
                    return new Constant(Double.parseDouble(number));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("计分公式中的数字不合法：" + number);
                }
            }
            if (c == '\0') {
                throw new IllegalArgumentException("计分公式不完整");
            }
            throw new IllegalArgumentException("计分公式包含不支持的字符：" + c);
        }

        private void expectEnd() {
            if (peek() != '\0') {
                throw new IllegalArgumentException("计分公式存在多余内容：" + text.substring(position));
            }
        }

        /** 跳过空白后查看当前字符；越界返回 '\0'。 */
        private char peek() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private Set<String> usedVariables() {
            return Set.copyOf(usedVariables);
        }
    }
}
