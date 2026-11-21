import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MonoTools — เครื่องมือแยกจาก Core (MonoClip)
 *
 *   ┌────────────────────┐
 *   │  User Buffer       │  UserBuffer : เก็บข้อความของผู้ใช้ (บรรทัด / undo / REPL feed)
 *   ├────────────────────┤
 *   │  Core interface    │  Core       : สะพานบางๆ  (parse / ast / warnings / dumpTree)
 *   ├────────────────────┤
 *   │  MonoClip          │  MonoClipCore : adapter ของ MonoClip
 *   └────────────────────┘
 *
 *   RuleBuilder + templates : สร้าง rule.json (JSON ที่ MonoClip อ่าน) แบบ fluent, ตรวจ (validate) ได้
 *
 * ใช้ผ่าน:  java MonoTools gen|check|run|repl ...   หรือเรียก API ตรงๆ
 */
public final class MonoTools {
    private MonoTools() {}

    // =========================================================================
    // Group helpers  (สร้าง group ใน grammar: ["choice", a, b] ...)
    // =========================================================================
    public static List<Object> group(String head, Object... items) {
        List<Object> l = new ArrayList<>();
        l.add(head);
        l.addAll(Arrays.asList(items));
        return l;
    }
    public static List<Object> choice(Object... i) { return group("choice", i); }
    public static List<Object> seq(Object... i)    { return group("seq", i); }
    public static List<Object> opt(Object... i)    { return group("opt", i); }
    public static List<Object> many(Object... i)   { return group("many", i); }
    public static List<Object> some(Object... i)   { return group("some", i); }
    public static List<Object> peek(Object... i)   { return group("peek", i); }
    public static List<Object> not(Object... i)    { return group("not", i); }
    public static List<Object> ignore(Object... i) { return group("ignore", i); }
    public static List<Object> cap(Object... i)    { return group("cap", i); }
    public static List<Object> until(Object... i)  { return group("until", i); }
    public static List<Object> sep(Object item, Object separator)  { return group("sep", item, separator); }
    public static List<Object> sep0(Object item, Object separator) { return group("sep0", item, separator); }
    public static List<Object> sept(Object item, Object separator) { return group("sept", item, separator); }
    public static List<Object> rep(int min, int max, Object... i) {
        List<Object> l = group("rep");
        l.add(min);
        l.add(max);
        l.addAll(Arrays.asList(i));
        return l;
    }

    // =========================================================================
    // JSON writer (ลำดับ key คงเดิม — สำคัญ เพราะลำดับ statements = ลำดับการลอง match)
    // =========================================================================
    public static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0);
        return sb.toString();
    }

    private static boolean hasMap(Object o) {
        if (o instanceof Map) return true;
        if (o instanceof List) for (Object x : (List<?>) o) if (hasMap(x)) return true;
        return false;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    private static void write(StringBuilder sb, Object o, int ind) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote(sb, (String) o); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, ind + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), ind + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, ind);
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            if (!hasMap(l)) {                                  // ไม่มี object ข้างใน -> บรรทัดเดียว
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, l.get(i), ind);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                indent(sb, ind + 1);
                write(sb, l.get(i), ind + 1);
                if (i + 1 < l.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, ind);
            sb.append(']');
            return;
        }
        quote(sb, String.valueOf(o));
    }

    /** แปลง Json.JsonObject / JsonArray เป็น Map / List ธรรมดา (ไว้ validate rule ที่อ่านจากไฟล์) */
    public static Object norm(Object o) {
        if (o instanceof Json.JsonObject) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : (Json.JsonObject) o) m.put(e.getKey(), norm(e.getValue()));
            return m;
        }
        if (o instanceof Json.JsonArray) {
            List<Object> l = new ArrayList<>();
            for (Object x : (Json.JsonArray) o) l.add(norm(x));
            return l;
        }
        return o;
    }

    // =========================================================================
    // RuleBuilder — สร้าง rule.json แบบ fluent
    //
    //   RuleBuilder b = new RuleBuilder().keywords("say").layout("newline");
    //   b.pratt("Expr").primary(choice("collect:number", "collect:ident")).level("+","-").level("*","/").end();
    //   b.statement("Say", "say", "%Expr", "@nl");
    //   String json = b.toJson();
    //
    //   ส่วน "lexer" : MonoTools ไม่รู้รูปแบบ config ของ MonoLexer -> ใส่เองด้วย lexer(...)
    //                  ถ้าไม่ใส่ จะสร้าง {"keywords": [...]} จาก keywords() ให้
    // =========================================================================
    public static final class RuleBuilder {
        private Map<String, Object> lexer;
        private final List<String> keywords = new ArrayList<>();
        private final Map<String, List<String>> kwGroups = new LinkedHashMap<>();
        private Boolean keywordsIgnoreCase;
        private final Map<String, Object> layout = new LinkedHashMap<>();
        private final Map<String, Object> tokenTypes = new LinkedHashMap<>();
        private final Map<String, Object> auto = new LinkedHashMap<>();
        private final Map<String, Object> blocks = new LinkedHashMap<>();
        private final Map<String, Object> classes = new LinkedHashMap<>();
        private final Map<String, Object> exprs = new LinkedHashMap<>();
        private final Map<String, Object> pratt = new LinkedHashMap<>();
        private final Map<String, Object> statements = new LinkedHashMap<>();
        private boolean collectMeta;

        // ---- lexer / keywords ----
        public RuleBuilder lexer(Map<String, Object> lexerConfig) {
            this.lexer = new LinkedHashMap<>(lexerConfig);
            return this;
        }

        public RuleBuilder lexer(String key, Object value) {
            if (lexer == null) lexer = new LinkedHashMap<>();
            lexer.put(key, value);
            return this;
        }

        public RuleBuilder keywords(String... words) {
            keywords.addAll(Arrays.asList(words));
            return this;
        }

        public RuleBuilder keywordGroup(String group, String... words) {
            kwGroups.computeIfAbsent(group, k -> new ArrayList<>()).addAll(Arrays.asList(words));
            return this;
        }

        public RuleBuilder keywordsIgnoreCase(boolean v) {
            keywordsIgnoreCase = v;
            return this;
        }

        // ---- layout ----
        public RuleBuilder layout(String mode) {
            layout.put("mode", mode);
            return this;
        }

        public RuleBuilder layout(String key, Object value) {
            layout.put(key, value);
            return this;
        }

        public String layoutMode() {
            return String.valueOf(layout.getOrDefault("mode", "none")).toLowerCase();
        }

        // ---- grammar parts ----
        public RuleBuilder tokenType(String name, String... types) {
            tokenTypes.put(name, new ArrayList<Object>(Arrays.asList((Object[]) types)));
            return this;
        }

        public RuleBuilder auto(String key, Object value) {
            auto.put(key, value);
            return this;
        }

        public RuleBuilder block(String name, String open, String close) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("open", open);
            b.put("close", close);
            blocks.put(name, b);
            return this;
        }

        /** class: ["a","b"] | "[a-z]" | {"re": ...} | {"first": ..., "rest": ...} */
        public RuleBuilder classDef(String name, Object def) {
            classes.put(name, def);
            return this;
        }

        public RuleBuilder collectMeta(boolean v) {
            collectMeta = v;
            return this;
        }

        public RuleBuilder expr(String name, Object... seq) {
            exprs.put(name, new ArrayList<Object>(Arrays.asList(seq)));
            return this;
        }

        /** statement เรียงตามลำดับที่เรียก = ลำดับที่ parser ลอง match */
        public RuleBuilder statement(String name, Object... seq) {
            statements.put(name, new ArrayList<Object>(Arrays.asList(seq)));
            return this;
        }

        public PrattBuilder pratt(String name) {
            PrattBuilder pb = new PrattBuilder(this);
            pratt.put(name, pb.m);
            return pb;
        }

        // ---- output ----
        public Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();

            Map<String, Object> lx = lexer;
            if (lx == null) {
                lx = new LinkedHashMap<>();
                if (!keywords.isEmpty()) lx.put("keywords", new ArrayList<Object>(keywords));
            }
            root.put("lexer", lx);
            if (!layout.isEmpty()) root.put("layout", layout);

            Map<String, Object> g = new LinkedHashMap<>();
            if (!kwGroups.isEmpty()) {
                Map<String, Object> kg = new LinkedHashMap<>();
                if (!keywords.isEmpty()) kg.put("Keyword", new ArrayList<Object>(keywords));
                for (Map.Entry<String, List<String>> e : kwGroups.entrySet()) kg.put(e.getKey(), new ArrayList<Object>(e.getValue()));
                g.put("keywords", kg);
            } else if (!keywords.isEmpty()) {
                g.put("keywords", new ArrayList<Object>(keywords));
            }
            if (keywordsIgnoreCase != null) g.put("keywordsIgnoreCase", keywordsIgnoreCase);
            if (collectMeta) g.put("collectMeta", true);
            if (!tokenTypes.isEmpty()) g.put("tokenTypes", tokenTypes);
            if (!auto.isEmpty()) g.put("auto", auto);
            if (!blocks.isEmpty()) g.put("blocks", blocks);
            if (!classes.isEmpty()) g.put("classes", classes);
            if (!exprs.isEmpty()) g.put("exprs", exprs);
            if (!pratt.isEmpty()) g.put("pratt", pratt);
            g.put("statements", statements);
            root.put("grammar", g);
            return root;
        }

        public String toJson() { return MonoTools.toJson(toMap()); }

        public void save(Path path) throws IOException { Files.writeString(path, toJson() + "\n"); }

        public List<String> validate() { return MonoTools.validate(toMap()); }
    }

    public static final class PrattBuilder {
        private final RuleBuilder owner;
        private final Map<String, Object> m = new LinkedHashMap<>();
        private final List<Object> levels = new ArrayList<>();
        private final List<Object> prefix = new ArrayList<>();
        private final List<Object> postfix = new ArrayList<>();

        private PrattBuilder(RuleBuilder owner) {
            this.owner = owner;
            m.put("levels", levels);
        }

        public PrattBuilder primary(Object element) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("primary", element);
            copy.putAll(m);
            m.clear();
            m.putAll(copy);
            return this;
        }

        public PrattBuilder group(String open, String close) {
            m.put("group", new ArrayList<Object>(Arrays.asList(open, close)));
            return this;
        }

        /** ระดับ precedence — เรียกจาก "ต่ำ -> สูง" */
        public PrattBuilder level(String... ops) {
            levels.add(new ArrayList<Object>(Arrays.asList((Object[]) ops)));
            return this;
        }

        public PrattBuilder levelRight(String... ops) {
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("ops", new ArrayList<Object>(Arrays.asList((Object[]) ops)));
            lv.put("assoc", "right");
            levels.add(lv);
            return this;
        }

        public PrattBuilder prefix(String... ops) {
            prefix.addAll(Arrays.asList((Object[]) ops));
            m.put("prefix", prefix);
            return this;
        }

        public PrattBuilder prefixPrec(int n) {
            m.put("prefixPrec", n);
            return this;
        }

        public PrattBuilder postfix(String op) {
            postfix.add(op);
            m.put("postfix", postfix);
            return this;
        }

        public PrattBuilder postfixSeq(String name, Object... seq) {
            Map<String, Object> pf = new LinkedHashMap<>();
            pf.put("name", name);
            pf.put("seq", new ArrayList<Object>(Arrays.asList(seq)));
            postfix.add(pf);
            m.put("postfix", postfix);
            return this;
        }

        public RuleBuilder end() { return owner; }
    }

    // =========================================================================
    // Templates
    // =========================================================================
    public static final List<String> TEMPLATES = Collections.unmodifiableList(
        Arrays.asList("calc", "python", "clike", "data", "empty"));

    public static RuleBuilder template(String name) {
        switch (name.toLowerCase()) {
            case "calc":   return calc();
            case "python": return python();
            case "clike":  return clike();
            case "data":   return data();
            case "empty":  return empty();
            default: throw new IllegalArgumentException("unknown template '" + name + "' (have: " + TEMPLATES + ")");
        }
    }

    private static PrattBuilder stdExpr(RuleBuilder b, boolean calls) {
        PrattBuilder p = b.pratt("Expr")
            .primary(choice("val:number", "val:string", "val:ident"))
            .group("(", ")")
            .level("or", "||")
            .level("and", "&&")
            .level("==", "!=", "<", ">", "<=", ">=")
            .level("+", "-")
            .level("*", "/", "%")
            .levelRight("**")
            .prefix("-", "!", "not")
            .prefixPrec(5);
        if (calls) p.postfixSeq("Call", "(", sep0("%Expr", ","), ")");
        return p;
    }

    /** เครื่องคิดเลข: let x = ..., say ... (ทีละบรรทัด) */
    public static RuleBuilder calc() {
        RuleBuilder b = new RuleBuilder().keywords("say", "let").layout("newline");
        stdExpr(b, false);
        b.statement("Let", "let", "collect:ident", "=", "%Expr", "@nl");
        b.statement("Say", "say", "%Expr", "@nl");
        return b;
    }

    /** ภาษาเยื้องแบบ Python: fn / if / else / while / return / let / say / เรียกฟังก์ชัน */
    public static RuleBuilder python() {
        RuleBuilder b = new RuleBuilder()
            .keywords("fn", "if", "else", "while", "return", "let", "say")
            .layout("mode", "indent").layout("tabSize", 4);
        stdExpr(b, true);
        b.statement("Fn", "fn", "collect:ident", "(", sep0("collect:ident", ","), ")", ":", "collect:block");
        b.statement("If", "if", "%Expr", ":", "collect:block");
        b.statement("Else", "else", ":", "collect:block");
        b.statement("While", "while", "%Expr", ":", "collect:block");
        b.statement("Return", "return", opt("%Expr"), "@nl");
        b.statement("Let", "let", "collect:ident", "=", "%Expr", "@nl");
        b.statement("Say", "say", "%Expr", "@nl");
        b.statement("ExprStmt", "%Expr", "@nl");
        return b;
    }

    /** ภาษา { } และ ; แบบ C: ปีกกาไม่ถือเป็น bracket ของ layout เพื่อให้ NEWLINE ยังเกิดในบล็อก */
    public static RuleBuilder clike() {
        RuleBuilder b = new RuleBuilder()
            .keywords("fn", "if", "else", "while", "return", "let", "say")
            .layout("mode", "newline")
            .layout("separators", new ArrayList<Object>(Arrays.asList(";")))
            .layout("brackets", new ArrayList<Object>(Arrays.asList(
                new ArrayList<Object>(Arrays.asList("(", ")")),
                new ArrayList<Object>(Arrays.asList("[", "]")))));
        stdExpr(b, true);
        b.statement("Fn", "fn", "collect:ident", "(", sep0("collect:ident", ","), ")", "collect:code");
        b.statement("If", "if", "(", "%Expr", ")", "collect:code");
        b.statement("Else", "else", "collect:code");
        b.statement("While", "while", "(", "%Expr", ")", "collect:code");
        b.statement("Return", "return", opt("%Expr"), "@nl");
        b.statement("Let", "let", "collect:ident", "=", "%Expr", "@nl");
        b.statement("Say", "say", "%Expr", "@nl");
        b.statement("ExprStmt", "%Expr", "@nl");
        return b;
    }

    /** ไฟล์ config: [section] และ key = value (value เป็น list/map/เลข/สตริงได้) */
    public static RuleBuilder data() {
        RuleBuilder b = new RuleBuilder().layout("newline");
        b.statement("Section", "[", "collect:ident", "]", "@nl");
        b.statement("Set", "collect:ident", "=", "collect:value", "@nl");
        return b;
    }

    /** โครงว่าง: อ่านทุกบรรทัดเป็นรายการ token (ใช้ดูผล lexer / เริ่มเขียน rule เอง) */
    public static RuleBuilder empty() {
        RuleBuilder b = new RuleBuilder().layout("newline");
        b.statement("Line", some("!@nl", "collect:token"), "@nl");
        return b;
    }

    // =========================================================================
    // Validate — ตรวจ rule ก่อนส่งให้ Core (ไม่ต้องมี MonoClip / Json จริง)
    // =========================================================================
    private static final Set<String> GROUPS = new HashSet<>(Arrays.asList(
        "choice", "oneof", "seq", "opt", "optional", "many", "star", "some", "plus", "rep",
        "sep", "sep0", "sept", "peek", "not", "ignore", "cap", "until", "any", "eof"));
    private static final Set<String> AT_NAMES = new HashSet<>(Arrays.asList(
        "eof", "any", "nl", "newline", "indent", "dedent"));
    private static final Pattern AT_LEVEL = Pattern.compile("^level(==|!=|>=|<=|>|<)\\d+$");

    private static boolean isIdentFrom(String s, int from) {
        if (from >= s.length()) return false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static boolean isCharClass(String s) {
        return s.length() >= 3 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']';
    }

    private static boolean isSpecForm(String b) {
        if (b.startsWith("%") || b.startsWith("$")) return isIdentFrom(b, 1);
        if (b.startsWith("@")) return b.length() > 1;
        return b.startsWith("collect:") || b.startsWith("val:") || b.startsWith("type:") || b.startsWith("kw:")
            || b.startsWith("class:") || b.startsWith("re:") || b.startsWith("sym:") || b.startsWith("lit:")
            || b.startsWith("ilit:") || isCharClass(b);
    }

    private static boolean isPostfixable(String b) {
        if (b.startsWith("%") || b.startsWith("$")) return isIdentFrom(b, 1);
        if (b.startsWith("@")) {
            String n = b.substring(1).toLowerCase();
            return AT_NAMES.contains(n);
        }
        if (b.startsWith("collect:") || b.startsWith("val:")) {
            String r = b.substring(b.indexOf(':') + 1);
            return !r.isEmpty() && !r.startsWith("re:") && !r.startsWith("sym:");
        }
        return b.startsWith("type:") || b.startsWith("kw:") || b.startsWith("class:") || isCharClass(b);
    }

    /** ตัด shorthand (&X !X ~X X* X+ X?) ให้เหลือ spec เดี่ยว — ใช้กฎเดียวกับ MonoClip */
    private static String peel(String s) {
        if (s.length() < 2) return s;
        String b = s;
        char c0 = s.charAt(0);
        if (c0 == '&' || c0 == '!' || c0 == '~') {
            String inner = s.substring(1);
            String base = stripPostfix(inner);
            return isSpecForm(base) ? base : s;
        }
        String base = stripPostfix(b);
        return base != b && isSpecForm(base) ? base : s;
    }

    private static String stripPostfix(String b) {
        int n = b.length();
        if (n >= 2) {
            char last = b.charAt(n - 1);
            if (last == '*' || last == '+' || last == '?') {
                String base = b.substring(0, n - 1);
                if (isPostfixable(base)) return base;
            }
        }
        return b;
    }

    private static final class Checker {
        final List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> classes = new HashSet<>();
        Set<String> blocks = new HashSet<>();
        String mode = "none";

        void problem(String where, String msg) { problems.add(where + ": " + msg); }

        void sequence(String where, Object o) {
            if (!(o instanceof List)) { problem(where, "must be an array"); return; }
            for (Object x : (List<?>) o) element(where, x);
        }

        void element(String where, Object el) {
            if (el instanceof List) { group(where, (List<?>) el); return; }
            if (!(el instanceof String)) {
                if (el instanceof Number || el instanceof Boolean) return;
                problem(where, "element must be a string or an array, got " + el);
                return;
            }
            spec(where, peel((String) el));
        }

        void group(String where, List<?> g) {
            if (g.isEmpty()) { problem(where, "empty group"); return; }
            String head = String.valueOf(g.get(0));
            if (!GROUPS.contains(head)) { problem(where, "unknown group '" + head + "'"); return; }
            int from = 1;
            switch (head) {
                case "rep":
                    if (g.size() < 4 || !(g.get(1) instanceof Number) || !(g.get(2) instanceof Number)) {
                        problem(where, "'rep' needs [\"rep\", min, max, ...items]");
                        return;
                    }
                    from = 3;
                    break;
                case "sep": case "sep0": case "sept":
                    if (g.size() != 3) { problem(where, "'" + head + "' needs exactly [\"" + head + "\", item, separator]"); return; }
                    break;
                default: break;
            }
            for (int i = from; i < g.size(); i++) element(where, g.get(i));
        }

        void spec(String where, String s) {
            String low = s.toLowerCase();
            if (s.startsWith("%") && isIdentFrom(s, 1)) {
                if (!names.contains(s.substring(1))) problem(where, "unknown rule '" + s + "'");
            } else if (s.startsWith("$") && isIdentFrom(s, 1)) {
                if (!classes.contains(s.substring(1))) problem(where, "unknown class '" + s + "'");
            } else if (low.startsWith("class:")) {
                if (!classes.contains(s.substring(6))) problem(where, "unknown class '" + s.substring(6) + "'");
            } else if (s.startsWith("@") && s.length() > 1) {
                String n = low.substring(1);
                if (AT_NAMES.contains(n)) {
                    boolean nl = n.equals("nl") || n.equals("newline");
                    if ((nl || n.equals("indent") || n.equals("dedent")) && mode.equals("none"))
                        problem(where, "'" + s + "' needs layout.mode newline or indent");
                    if ((n.equals("indent") || n.equals("dedent")) && !mode.equals("indent"))
                        problem(where, "'" + s + "' needs layout.mode indent");
                } else if (!AT_LEVEL.matcher(n).matches()) {
                    problem(where, "unknown element '" + s + "'");
                }
            } else if (low.startsWith("collect:") || low.startsWith("val:")) {
                String r = s.substring(s.indexOf(':') + 1);
                String rl = r.toLowerCase();
                if (rl.equals("block") && !mode.equals("indent")) {
                    problem(where, "'" + s + "' needs layout.mode indent");
                } else if (rl.startsWith("code:") || rl.startsWith("raw:")) {
                    String bn = r.substring(r.indexOf(':') + 1);
                    if (!blocks.contains(bn)) problem(where, "unknown block '" + bn + "' (grammar.blocks)");
                } else if (rl.startsWith("class:") || r.startsWith("$")) {
                    spec(where, r);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /** ตรวจ rule (Map ตามโครง rule.json) -> รายการปัญหา (ว่าง = ไม่พบ) */
    public static List<String> validate(Map<String, Object> root) {
        Checker c = new Checker();
        Object go = root.get("grammar");
        if (!(go instanceof Map)) { c.problem("rule", "missing 'grammar' object"); return c.problems; }
        Map<String, Object> g = mapOf(go);
        if (!(g.get("statements") instanceof Map)) {
            c.problem("grammar.statements", "must be an object");
            return c.problems;
        }
        Map<String, Object> st = mapOf(g.get("statements"));
        Map<String, Object> ex = mapOf(g.get("exprs"));
        Map<String, Object> pr = mapOf(g.get("pratt"));

        c.classes.addAll(mapOf(g.get("classes")).keySet());
        c.blocks.addAll(mapOf(g.get("blocks")).keySet());
        c.names.addAll(st.keySet());
        c.names.addAll(ex.keySet());
        c.names.addAll(pr.keySet());
        c.names.add("Statement");
        c.names.add("Stmt");

        Object lx = root.get("lexer");
        if (lx != null && !(lx instanceof Map)) c.problem("lexer", "must be an object");
        if (lx instanceof Map) {
            for (String k : new String[]{"keyword", "operator", "symbol"}) {
                Object sec = ((Map<?, ?>) lx).get(k);
                if (sec == null) continue;
                if (k.equals("keyword") && sec instanceof List) continue;
                if (!(sec instanceof Map)) { c.problem("lexer." + k, "must be an object {text: TOKEN_TYPE}"); continue; }
                for (Map.Entry<?, ?> e : ((Map<?, ?>) sec).entrySet()) {
                    if (String.valueOf(e.getKey()).isEmpty()) c.problem("lexer." + k, "empty key");
                    if (!(e.getValue() instanceof String)) c.problem("lexer." + k + "." + e.getKey(), "token type must be a string");
                }
            }
        }

        Object lo = root.get("layout") != null ? root.get("layout") : g.get("layout");
        c.mode = layoutMode(lo);
        if (!c.mode.equals("none") && !c.mode.equals("newline") && !c.mode.equals("indent"))
            c.problem("layout.mode", "must be none | newline | indent (got '" + c.mode + "')");

        if (st.isEmpty()) c.problem("grammar.statements", "is empty — nothing can be parsed");

        for (Map.Entry<String, Object> e : st.entrySet()) c.sequence("statements." + e.getKey(), e.getValue());
        for (Map.Entry<String, Object> e : ex.entrySet()) c.sequence("exprs." + e.getKey(), e.getValue());

        for (Map.Entry<String, Object> e : pr.entrySet()) {
            String w = "pratt." + e.getKey();
            if (!(e.getValue() instanceof Map)) { c.problem(w, "must be an object"); continue; }
            Map<String, Object> p = mapOf(e.getValue());
            if (!p.containsKey("primary")) c.problem(w, "missing 'primary'");
            else c.element(w + ".primary", p.get("primary"));
            if (p.containsKey("group") && !(p.get("group") instanceof List && ((List<?>) p.get("group")).size() == 2))
                c.problem(w + ".group", "must be [open, close]");
            if (p.get("levels") instanceof List) {
                int i = 0;
                for (Object lv : (List<?>) p.get("levels")) {
                    boolean ok = lv instanceof List || (lv instanceof Map && ((Map<?, ?>) lv).get("ops") instanceof List);
                    if (!ok) c.problem(w + ".levels[" + i + "]", "must be an array of ops or {ops, assoc}");
                    i++;
                }
            }
            if (p.get("postfix") instanceof List) {
                for (Object pf : (List<?>) p.get("postfix")) {
                    if (pf instanceof Map) {
                        if (!((Map<?, ?>) pf).containsKey("seq")) c.problem(w + ".postfix", "object needs 'seq'");
                        else c.sequence(w + ".postfix", ((Map<?, ?>) pf).get("seq"));
                    } else if (!(pf instanceof String)) {
                        c.problem(w + ".postfix", "must be a string or {name, seq}");
                    }
                }
            }
        }

        // left recursion แบบตรงๆ: rule ที่ element แรกคือ %ตัวมันเอง (ผ่านสายอ้างอิงของ statements/exprs)
        Map<String, Object> seqRules = new LinkedHashMap<>(ex);
        seqRules.putAll(st);
        for (String start : seqRules.keySet()) {
            if (leftRecursive(start, start, seqRules, new HashSet<>()))
                c.problem("statements/exprs." + start, "left recursion (starts with itself) — use pratt or many/sep instead");
        }
        return c.problems;
    }

    private static boolean leftRecursive(String target, String cur, Map<String, Object> rules, Set<String> seen) {
        Object r = rules.get(cur);
        if (!(r instanceof List) || ((List<?>) r).isEmpty()) return false;
        Object first = ((List<?>) r).get(0);
        if (!(first instanceof String)) return false;
        String s = peel((String) first);
        if (!(s.startsWith("%") && isIdentFrom(s, 1))) return false;
        String next = s.substring(1);
        if (next.equals(target)) return true;
        if (!rules.containsKey(next) || !seen.add(next)) return false;
        return leftRecursive(target, next, rules, seen);
    }

    private static String layoutMode(Object layoutObj) {
        if (layoutObj instanceof Map) {
            Object m = ((Map<?, ?>) layoutObj).get("mode");
            return m == null ? "none" : String.valueOf(m).toLowerCase();
        }
        return "none";
    }

    // =========================================================================
    // Core interface + MonoClip adapter
    // =========================================================================
    public interface Core {
        /** @throws MonoClip.MonoClipException (หรือ RuntimeException อื่น จะถูกห่อให้เป็น LexerError) */
        void parse(String source);
        List<MonoClip.Nodes> ast();
        List<String> warnings();
        String dumpTree();
    }

    public static final class MonoClipCore implements Core {
        private final MonoClip clip;
        public MonoClipCore(MonoClip clip) { this.clip = clip; }
        public MonoClip clip() { return clip; }
        @Override public void parse(String source) { clip.parse(source); }
        @Override public List<MonoClip.Nodes> ast() { return clip.Ast; }
        @Override public List<String> warnings() { return clip.Warnings; }
        @Override public String dumpTree() { return clip.dumpAstTree(); }
    }

    // =========================================================================
    // ParseResult
    // =========================================================================
    public static final class ParseResult {
        public final boolean ok;
        public final List<MonoClip.Nodes> ast;          // statement ที่ parse ได้ (ok=false = ได้ก่อนจุดผิด)
        public final MonoClip.MonoClipException error;  // null ถ้า ok
        public final List<String> warnings;
        public final String source;
        public final int version;                       // เวอร์ชันของ buffer ตอน parse
        public final int lineBase;

        ParseResult(boolean ok, List<MonoClip.Nodes> ast, MonoClip.MonoClipException error,
                    List<String> warnings, String source, int version, int lineBase) {
            this.ok = ok;
            this.ast = Collections.unmodifiableList(ast);
            this.error = error;
            this.warnings = Collections.unmodifiableList(warnings);
            this.source = source;
            this.version = version;
            this.lineBase = lineBase;
        }

        /** error เพราะ input ยังไม่จบ (จบไฟล์กลางประโยค) -> REPL ควรรับบรรทัดต่อ */
        public boolean incomplete() {
            return error != null && "SyntaxError".equals(error.kind) && error.detail.contains("end of file");
        }

        /** ข้อความอ่านง่าย: error + บรรทัดต้นฉบับ + ลูกศรชี้คอลัมน์ */
        public String report() {
            if (ok) return "OK (" + ast.size() + " statements)";
            StringBuilder sb = new StringBuilder(error.getMessage());
            if (error.line > 0) {
                String[] ls = source.split("\r\n|\r|\n", -1);
                int idx = error.line - lineBase;
                if (idx >= 0 && idx < ls.length) {
                    sb.append('\n').append(ls[idx]).append('\n');
                    int pad = Math.max(0, error.col - 1);
                    for (int i = 0; i < pad; i++) sb.append(' ');
                    sb.append('^');
                }
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // UserBuffer — ชั้นที่ผู้ใช้แตะ: เก็บข้อความ แก้ไข undo/redo แล้วส่งให้ Core
    // =========================================================================
    public static final class UserBuffer {
        public enum Status { OK, INCOMPLETE, ERROR, EMPTY }

        public static final class Feed {
            public final Status status;
            public final ParseResult result;     // null เมื่อ EMPTY
            Feed(Status status, ParseResult result) { this.status = status; this.result = result; }
        }

        private final Core core;
        private List<String> lines = new ArrayList<>();
        private final Deque<List<String>> undoStack = new ArrayDeque<>();
        private final Deque<List<String>> redoStack = new ArrayDeque<>();
        private final List<Consumer<ParseResult>> listeners = new ArrayList<>();
        private final List<String> pending = new ArrayList<>();
        private int version = 0;
        private ParseResult last;

        public int lineBase = 1;                 // ต้องตรงกับ layout.lineBase ของ rule
        public int maxHistory = 100;
        /** ภาษาแบบเยื้อง: บล็อกในโหมด feed() จบด้วยบรรทัดว่าง */
        public boolean blankLineEndsBlock = false;

        public UserBuffer(Core core) { this.core = core; }

        // ---- อ่าน ----
        public int lineCount() { return lines.size(); }
        public String line(int i) { return lines.get(i); }
        public List<String> lines() { return Collections.unmodifiableList(lines); }
        public int version() { return version; }
        public ParseResult last() { return last; }
        public String text() { return lines.isEmpty() ? "" : String.join("\n", lines) + "\n"; }
        public Core core() { return core; }

        // ---- แก้ไข (ทุกอย่างบันทึก undo + เพิ่ม version) ----
        private static List<String> split(String text) {
            List<String> l = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
            if (l.size() > 1 && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
            if (l.size() == 1 && l.get(0).isEmpty()) l.clear();
            return l;
        }

        private void mutate() {
            undoStack.push(new ArrayList<>(lines));
            while (undoStack.size() > maxHistory) undoStack.removeLast();
            redoStack.clear();
            version++;
        }

        public UserBuffer set(String text) { mutate(); lines = split(text); return this; }
        public UserBuffer append(String text) { mutate(); lines.addAll(split(text)); return this; }
        public UserBuffer appendLine(String line) { mutate(); lines.add(line); return this; }
        public UserBuffer insertLine(int index, String line) { mutate(); lines.add(index, line); return this; }
        public UserBuffer replaceLine(int index, String line) { mutate(); lines.set(index, line); return this; }
        public UserBuffer removeLine(int index) { mutate(); lines.remove(index); return this; }
        public UserBuffer clear() { mutate(); lines.clear(); return this; }

        public boolean undo() {
            if (undoStack.isEmpty()) return false;
            redoStack.push(new ArrayList<>(lines));
            lines = undoStack.pop();
            version++;
            return true;
        }

        public boolean redo() {
            if (redoStack.isEmpty()) return false;
            undoStack.push(new ArrayList<>(lines));
            lines = redoStack.pop();
            version++;
            return true;
        }

        public void onParse(Consumer<ParseResult> listener) { listeners.add(listener); }

        // ---- ส่งให้ Core ----
        private ParseResult run(String source) {
            MonoClip.MonoClipException err = null;
            try {
                core.parse(source);
            } catch (MonoClip.MonoClipException e) {
                err = e;
            } catch (RuntimeException e) {                // เช่น error จาก MonoLexer
                err = new MonoClip.MonoClipException("LexerError", String.valueOf(e.getMessage()), 0, 0, 1, e);
            }
            return new ParseResult(err == null, new ArrayList<>(core.ast()), err,
                                   new ArrayList<>(core.warnings()), source, version, lineBase);
        }

        /** parse ทั้ง buffer */
        public ParseResult parse() {
            last = run(text());
            for (Consumer<ParseResult> l : listeners) l.accept(last);
            return last;
        }

        // ---- โหมด REPL: ป้อนทีละบรรทัด ----
        public List<String> pendingLines() { return Collections.unmodifiableList(pending); }
        public void cancelPending() { pending.clear(); }

        /**
         * ป้อน 1 บรรทัด
         *  OK         : chunk ที่ค้างอยู่ parse ผ่าน -> เข้า buffer, result.ast คือผลของ chunk นั้น
         *  INCOMPLETE : ยังไม่จบ (ประโยคค้าง / บล็อกที่ยังไม่ได้ปิดด้วยบรรทัดว่าง) -> ป้อนบรรทัดต่อไป
         *  ERROR      : ผิด -> ทิ้ง chunk ที่ค้าง
         *  EMPTY      : บรรทัดว่างโดยไม่มีอะไรค้าง
         */
        public Feed feed(String line) {
            boolean blank = line.trim().isEmpty();
            if (blank && pending.isEmpty()) return new Feed(Status.EMPTY, null);
            pending.add(line);

            if (blankLineEndsBlock && !blank && (pending.size() > 1 || line.stripTrailing().endsWith(":"))) {
                return new Feed(Status.INCOMPLETE, null);   // อยู่ในบล็อก รอบรรทัดว่าง
            }

            String chunk = String.join("\n", pending) + "\n";
            ParseResult r = run(chunk);
            if (r.ok) {
                mutate();
                lines.addAll(pending);
                pending.clear();
                last = r;
                for (Consumer<ParseResult> l : listeners) l.accept(r);
                return new Feed(Status.OK, r);
            }
            if (r.incomplete() && !(blankLineEndsBlock && blank)) return new Feed(Status.INCOMPLETE, r);
            pending.clear();
            last = r;
            for (Consumer<ParseResult> l : listeners) l.accept(r);
            return new Feed(Status.ERROR, r);
        }
    }

    // =========================================================================
    // ต่อ Buffer เข้ากับ Core
    // =========================================================================
    /** สร้าง Core + Buffer จาก RuleBuilder (rule ผิด -> MonoClipException) */
    public static UserBuffer open(RuleBuilder rule) {
        MonoClip clip = new MonoClip();
        clip.loadRuleString(rule.toJson());
        UserBuffer b = new UserBuffer(new MonoClipCore(clip));
        b.blankLineEndsBlock = rule.layoutMode().equals("indent");
        Object lb = rule.layout.get("lineBase");
        if (lb instanceof Number) b.lineBase = ((Number) lb).intValue();
        return b;
    }

    /** สร้าง Core + Buffer จากไฟล์ rule.json */
    public static UserBuffer open(Path rulePath) throws Exception {
        MonoClip clip = new MonoClip();
        clip.loadRulePath(rulePath);
        UserBuffer b = new UserBuffer(new MonoClipCore(clip));
        Object root = norm(Json.parse(Files.readString(rulePath)));
        Map<String, Object> rm = mapOf(root);
        Object lo = rm.get("layout") != null ? rm.get("layout") : mapOf(rm.get("grammar")).get("layout");
        b.blankLineEndsBlock = layoutMode(lo).equals("indent");
        if (lo instanceof Map && ((Map<?, ?>) lo).get("lineBase") instanceof Number)
            b.lineBase = ((Number) ((Map<?, ?>) lo).get("lineBase")).intValue();
        return b;
    }

    // =========================================================================
    // CLI
    //   java MonoTools gen <template> [out.json]
    //   java MonoTools check <rule.json>
    //   java MonoTools run <rule.json> <source>
    //   java MonoTools repl <rule.json>
    // =========================================================================
    private static void usage() {
        System.err.println("usage:\n"
            + "  MonoTools gen <" + String.join("|", TEMPLATES) + "> [out.json]\n"
            + "  MonoTools check <rule.json>\n"
            + "  MonoTools run <rule.json> <source>\n"
            + "  MonoTools repl <rule.json>");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); }
        switch (args[0]) {
            case "gen": {
                RuleBuilder b = template(args[1]);
                if (args.length >= 3) {
                    b.save(Path.of(args[2]));
                    System.out.println("wrote " + args[2]);
                } else {
                    System.out.println(b.toJson());
                }
                break;
            }
            case "check": {
                String text = Files.readString(Path.of(args[1]));
                int bad = 0;
                try {
                    Object root = norm(Json.parse(text));
                    for (String p : validate(mapOf(root))) { System.out.println("problem: " + p); bad++; }
                } catch (RuntimeException e) {
                    System.out.println("problem: invalid JSON: " + e.getMessage());
                    bad++;
                }
                try {
                    MonoClip mc = new MonoClip();
                    mc.loadRuleString(text);
                    MonoLexer.loadConfig(mc.LexerRule);
                } catch (MonoClip.MonoClipException | MonoLexer.LexerException e) {
                    System.out.println("problem: " + e.getMessage());
                    bad++;
                }
                System.out.println(bad == 0 ? "OK" : bad + " problem(s)");
                System.exit(bad == 0 ? 0 : 1);
                break;
            }
            case "run": {
                if (args.length < 3) { usage(); System.exit(2); }
                UserBuffer buf = open(Path.of(args[1]));
                buf.set(Files.readString(Path.of(args[2])));
                ParseResult r = buf.parse();
                System.out.println(r.report());
                for (String w : r.warnings) System.out.println(w);
                if (r.ok) System.out.print(buf.core().dumpTree());
                System.exit(r.ok ? 0 : 1);
                break;
            }
            case "repl": {
                UserBuffer buf = open(Path.of(args[1]));
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                System.out.print(">>> ");
                System.out.flush();
                for (String line; (line = in.readLine()) != null; ) {
                    UserBuffer.Feed f = buf.feed(line);
                    switch (f.status) {
                        case OK:         System.out.print(buf.core().dumpTree()); break;
                        case ERROR:      System.out.println(f.result.report()); break;
                        case INCOMPLETE: break;
                        default:         break;
                    }
                    System.out.print(f.status == UserBuffer.Status.INCOMPLETE ? "... " : ">>> ");
                    System.out.flush();
                }
                System.out.println();
                break;
            }
            default:
                usage();
                System.exit(2);
        }
    }
}