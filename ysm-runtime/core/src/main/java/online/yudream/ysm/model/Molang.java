package online.yudream.ysm.model;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** Bounded arithmetic Molang subset. No loops, reflection, scripts or host object bindings. */
public final class Molang {
    private Molang() { }
    private static final Map<String,Integer> FUNCTIONS = Map.ofEntries(
        Map.entry("math.sin",1),Map.entry("math.cos",1),Map.entry("math.abs",1),Map.entry("math.floor",1),
        Map.entry("math.ceil",1),Map.entry("math.round",1),Map.entry("math.sqrt",1),Map.entry("math.exp",1),
        Map.entry("math.min",2),Map.entry("math.max",2),Map.entry("math.pow",2),Map.entry("math.clamp",3),
        Map.entry("math.lerp",3),Map.entry("math.mod",2),Map.entry("ysm.second_order",5),
        Map.entry("query.position_delta",1),Map.entry("q.position_delta",1));
    public static final class Context {
        public double time;
        public final Map<String,Double> variables = new HashMap<>();
        public Context(double time) { this.time=time; }
        double get(String name) {
            name=alias(name);
            if(name.equals("query.anim_time")||name.equals("query.life_time"))return time;
            if(name.equals("math.pi"))return Math.PI;
            return variables.getOrDefault(name, switch(name) { case "v.roaming.player_size" -> 1d; case "query.health","query.max_health","ysm.food_level" -> 20d; default -> 0d; });
        }
    }
    private static String alias(String name) { return name.startsWith("variable.")?"v."+name.substring(9):name.startsWith("q.")?"query."+name.substring(2):name; }
    public record Expr(String op, String text, double number, List<Expr> args) {
        public double eval(Context c) {
            double result=switch(op) {
                case "n" -> number; case "s" -> 0; case "v" -> c.get(text);
                case "neg" -> -args.getFirst().eval(c); case "!" -> truth(args.getFirst().eval(c))?0:1;
                case "+" -> args.get(0).eval(c)+args.get(1).eval(c); case "-" -> args.get(0).eval(c)-args.get(1).eval(c);
                case "*" -> args.get(0).eval(c)*args.get(1).eval(c); case "/" -> {double a=args.get(0).eval(c),b=args.get(1).eval(c);yield b==0?0:a/b;}
                case "%" -> {double a=args.get(0).eval(c),b=args.get(1).eval(c);yield b==0?0:a%b;}
                case "&&" -> truth(args.get(0).eval(c))&&truth(args.get(1).eval(c))?1:0;
                case "||" -> truth(args.get(0).eval(c))||truth(args.get(1).eval(c))?1:0;
                case "==" -> args.get(0).eval(c)==args.get(1).eval(c)?1:0; case "!=" -> args.get(0).eval(c)!=args.get(1).eval(c)?1:0;
                case "<" -> args.get(0).eval(c)<args.get(1).eval(c)?1:0; case ">" -> args.get(0).eval(c)>args.get(1).eval(c)?1:0;
                case "<=" -> args.get(0).eval(c)<=args.get(1).eval(c)?1:0; case ">=" -> args.get(0).eval(c)>=args.get(1).eval(c)?1:0;
                case "?" -> args.get(truth(args.get(0).eval(c))?1:2).eval(c);
                case "=" -> {double v=args.getFirst().eval(c);c.variables.put(alias(text),v);yield v;}
                case ";" -> {double v=0;for(Expr e:args)v=e.eval(c);yield v;}
                case "call" -> call(text,args,c);
                default -> throw new IllegalArgumentException("Invalid expression opcode");
            };
            return Double.isFinite(result)?Math.max(-1e6,Math.min(1e6,result)):0;
        }
    }
    private static boolean truth(double n) { return n!=0; }
    private static double call(String name,List<Expr> args,Context c) {
        if(name.equals("ysm.second_order"))return args.get(1).eval(c); // Documented target-value approximation, no spring state.
        double a=args.get(0).eval(c),b=args.size()>1?args.get(1).eval(c):0,d=args.size()>2?args.get(2).eval(c):0;
        return switch(name) {
            case "math.sin" -> Math.sin(Math.toRadians(a)); case "math.cos" -> Math.cos(Math.toRadians(a));
            case "math.abs" -> Math.abs(a); case "math.floor" -> Math.floor(a); case "math.ceil" -> Math.ceil(a);
            case "math.round" -> Math.floor(a+.5); case "math.sqrt" -> Math.sqrt(Math.max(0,a)); case "math.exp" -> Math.exp(a);
            case "math.min" -> Math.min(a,b); case "math.max" -> Math.max(a,b); case "math.pow" -> Math.pow(a,b);
            case "math.clamp" -> Math.max(b,Math.min(d,a)); case "math.lerp" -> a+(b-a)*d; case "math.mod" -> b==0?0:a%b;
            case "query.position_delta","q.position_delta" -> 0;
            default -> throw new IllegalArgumentException("Unsupported expression function");
        };
    }
    public static JsonObject parse(String source) {
        if(source.length()>1024)throw new IllegalArgumentException("表达式超过 1024 字符");
        var p=new Reader(source);JsonArray ast=p.sequence();if(p.peek()!=null)throw new IllegalArgumentException("不支持的表达式语法");
        JsonObject out=new JsonObject();out.add("expr",ast);compile(out);return out;
    }
    public static Expr compile(JsonElement value) {
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()) {
            double n=value.getAsDouble();if(!Double.isFinite(n)||Math.abs(n)>1e6)throw new IllegalArgumentException("表达式数值超限");
            return new Expr("n",null,n,List.of());
        }
        if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("expr")))throw new IllegalArgumentException("表达式数据无效");
        return node(value.getAsJsonObject().get("expr"),0,new int[]{0});
    }
    private static Expr node(JsonElement value,int depth,int[] count) {
        if(depth>24||++count[0]>256||!value.isJsonArray())throw new IllegalArgumentException("表达式复杂度超限");
        JsonArray a=value.getAsJsonArray();if(a.size()<2||!a.get(0).isJsonPrimitive())throw new IllegalArgumentException("表达式节点无效");
        String op=a.get(0).getAsString(),text=null;double number=0;int first=1,arity;
        switch(op) {
            case "n" -> {number=a.get(1).getAsDouble();if(!a.get(1).getAsJsonPrimitive().isNumber()||!Double.isFinite(number)||Math.abs(number)>1e6)throw new IllegalArgumentException("表达式数值超限");arity=0;first=2;}
            case "s","v","=","call" -> {
                text=a.get(1).getAsString();if(text.length()>128)throw new IllegalArgumentException("表达式名称过长");first=2;
                if(op.equals("call")){Integer n=FUNCTIONS.get(text);if(n==null)throw new IllegalArgumentException("不支持的 Molang 函数："+text);arity=n;}
                else if(op.equals("=")){if(!text.matches("(?:v|variable|t|temp)\\.[a-z0-9_.]+"))throw new IllegalArgumentException("仅允许赋值给局部变量");arity=1;}
                else {if(op.equals("v")&&!text.matches("(?:v|variable|t|temp|q|query|ysm|math)\\.[a-z0-9_.]+"))throw new IllegalArgumentException("不支持的变量");arity=0;}
            }
            case "neg","!" -> arity=1;
            case "+","-","*","/","%","<",">","<=",">=","==","!=","&&","||" -> arity=2;
            case "?" -> arity=3;
            case ";" -> arity=a.size()-1;
            default -> throw new IllegalArgumentException("不支持的表达式节点");
        }
        if(a.size()!=first+arity||arity>32)throw new IllegalArgumentException("表达式参数数量无效");
        List<Expr> children=new ArrayList<>();for(int i=first;i<a.size();i++)children.add(node(a.get(i),depth+1,count));
        return new Expr(op,text,number,List.copyOf(children));
    }
    private static JsonArray ast(String op,Object... values) {
        var a=new JsonArray();a.add(op);for(Object v:values){if(v instanceof JsonElement e)a.add(e);else if(v instanceof Number n)a.add(n);else a.add((String)v);}return a;
    }
    private static final class Reader {
        private static final Pattern TOKEN=Pattern.compile("\\s*(?:(\\d+(?:\\.\\d*)?(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?)|([a-zA-Z_][a-zA-Z0-9_.]*)|('(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")|(==|!=|<=|>=|&&|\\|\\||[+*/%<>=!?:(),;\\-]))");
        final List<String> tokens=new ArrayList<>();int pos,depth;
        Reader(String source){var m=TOKEN.matcher(source);int end=0;while(m.find()){if(m.start()!=end)throw new IllegalArgumentException("表达式含非法字符");tokens.add(m.group().trim());end=m.end();}if(!source.substring(end).isBlank()||tokens.size()>384)throw new IllegalArgumentException("表达式无效或过长");}
        String peek(){return pos<tokens.size()?tokens.get(pos):null;}
        boolean eat(String s){if(s.equals(peek())){pos++;return true;}return false;}
        void need(String s){if(!eat(s))throw new IllegalArgumentException("表达式缺少 "+s);}
        JsonArray sequence(){List<JsonArray> items=new ArrayList<>();do {if(peek()==null)break;eat("return");items.add(expression(0));}while(eat(";")&&peek()!=null);if(items.isEmpty())throw new IllegalArgumentException("表达式为空");if(items.size()==1)return items.getFirst();JsonArray a=ast(";");items.forEach(a::add);return a;}
        JsonArray expression(int min) {
            if(++depth>24)throw new IllegalArgumentException("表达式嵌套过深");JsonArray left;
            if(eat("-"))left=ast("neg",expression(9));else if(eat("+"))left=expression(9);else if(eat("!"))left=ast("!",expression(9));
            else if(eat("(")){left=expression(0);need(")");}
            else {String token=peek();if(token==null)throw new IllegalArgumentException("表达式意外结束");pos++;
                if(token.matches("(?:[0-9].*|\\.[0-9].*)"))left=ast("n",Double.parseDouble(token));
                else if(token.startsWith("'")||token.startsWith("\""))left=ast("s",token.substring(1,token.length()-1));
                else if(token.matches("[a-zA-Z_][a-zA-Z0-9_.]*")){
                    token=token.toLowerCase(Locale.ROOT);
                    if(eat("(")){left=ast("call",token);if(!eat(")")){do {left.add(expression(0));}while(eat(","));need(")");}}
                    else if(token.equals("true")||token.equals("false"))left=ast("n",token.equals("true")?1:0);
                    else left=ast("v",token);
                } else throw new IllegalArgumentException("不支持的表达式语法");
            }
            while(peek()!=null){String op=peek();int precedence=switch(op){case "="->1;case "?"->2;case "||"->3;case "&&"->4;case "==","!="->5;case "<",">","<=",">="->6;case "+","-"->7;case "*","/","%"->8;default->-1;};if(precedence<min)break;pos++;
                if(op.equals("?")){JsonArray yes=expression(0);JsonArray no=eat(":")?expression(2):ast("n",0);left=ast("?",left,yes,no);}
                else if(op.equals("=")){if(!left.get(0).getAsString().equals("v"))throw new IllegalArgumentException("赋值目标无效");left=ast("=",left.get(1).getAsString(),expression(precedence));}
                else left=ast(op,left,expression(precedence+1));
            }
            depth--;return left;
        }
    }
}
