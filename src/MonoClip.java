import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class MonoClip {
    public Json.JsonObject MainRule;
    public Json.JsonObject GrammarRule;
    public Json.JsonObject StatementRule;
    public Json.JsonObject ExprRule;     // grammar.exprs   : rule ย่อยแบบ sequence
    public Json.JsonObject PrattRule;    // grammar.pratt   : นิพจน์แบบมี precedence
    public Json.JsonObject ClassRule;    // grammar.classes : custom class / symbol
    public Json.JsonObject LexerRule;
    public boolean LoadDefRuleIfRuleFileNotFound_Enable = false;
    private String DefRule = "";
    private List<Tok> Tokens;
    private int pc = 0;
    private int farPc = 0;
    private int stmtDepth = 0;
    public List<Nodes> Ast = new ArrayList<>();
    public boolean DebugAst_Enable = false;

    // ---- คำเตือน (Warning) : เก็บไว้ใน Warnings และพิมพ์ลง stderr ถ้า WarningPrint_Enable = true ----
    public final List<String> Warnings = new ArrayList<>();
    public boolean WarningPrint_Enable = true;

    // ---- ข้อมูลที่โหลดจาก JSON ----
    private final Set<String> KeywordAll = new HashSet<>();
    private final Map<String, Set<String>> KeywordGroups = new HashMap<>();
    private boolean KeywordIgnoreCase = false;
    private final Map<String, Set<String>> TypeMap = new HashMap<>();
    private Layout layout = new Layout();
    private boolean collectMeta = false;

    // ---- auto collect config (grammar.auto / grammar.blocks) ----
    private String[] autoList = {"[", "]"};
    private String[] autoMap = {"{", "}"};
    private String[] autoTuple = {"(", ")"};
    private String[] codeB = {"{", "}"};
    private Set<String> autoSep = new HashSet<>(Arrays.asList(","));
    private Set<String> autoPair = new HashSet<>(Arrays.asList(":"));
    private boolean autoTrailing = true;
    private String rawJoin = " ";
    private final Map<String, String[]> blocks = new HashMap<>();

    // ---- cache ----
    private final Map<String, Pattern> regexCache = new HashMap<>();
    private final Map<String, CharSet> charSetCache = new HashMap<>();
    private final Map<String, Pratt> prattCache = new HashMap<>();
    private final Map<String, List<Object>> shorthandCache = new HashMap<>();

    private static final Object NOPE = new Object();   // "parse ไม่ได้" (แยกจากค่า null ที่เป็นค่าจริง)

    /**
     * Exception ของ MonoClip ทุกชนิด (unchecked)
     *   kind   : ชนิดข้อผิดพลาด  SyntaxError | IndentationError | RuleError | JsonError | MonoClip
     *   detail : ข้อความล้วนๆ (ไม่มี prefix / ตำแหน่ง)
     *   line,col : ตำแหน่งใน source (0 = ไม่มีตำแหน่ง เช่น error ตอนโหลด rule)
     *   code   : รหัส (เดิมคือ exit code)
     *   getMessage() ได้ข้อความรูปแบบเดิม  "[SyntaxError] Unexpected token 'x' InLine 3:5"
     */
    public static class MonoClipException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String kind;
        public final String detail;
        public final int line, col, code;

        public MonoClipException(String kind, String detail, int line, int col, int code) {
            super(format(kind, detail, line, col));
            this.kind = kind;
            this.detail = detail;
            this.line = line;
            this.col = col;
            this.code = code;
        }

        public MonoClipException(String kind, String detail, int line, int col, int code, Throwable cause) {
            this(kind, detail, line, col, code);
            initCause(cause);
        }

        private static String format(String kind, String detail, int line, int col) {
            return "[" + kind + "] " + detail + (line > 0 ? " InLine " + line + ":" + col : "");
        }
    }

    /** token ของเราเอง (คัดลอกจาก MonoLexer.Token + ข้อมูลเยื้อง) รวม token สังเคราะห์ NEWLINE/INDENT/DEDENT */
    public static final class Tok {
        public final String type;      // UPPERCASE
        public final String value;
        public final int line, col;
        public int indent = 0;         // ความกว้างการเยื้องของบรรทัดตรรกะที่ token อยู่ (นับ tab แล้ว)
        public int level = 0;          // ระดับ block (จำนวน INDENT ที่ซ้อนอยู่) = tab counter
        public boolean synthetic = false;
        public Tok(String type, String value, int line, int col) {
            this.type = type; this.value = value; this.line = line; this.col = col;
        }
        @Override public String toString() { return value; }
    }

    /** ค่าที่ห่อด้วยข้อมูลชนิด + ตำแหน่ง (val:SPEC หรือ grammar.collectMeta = true) */
    public static final class Val {
        public final Object value;
        public final String type;
        public final int line, col;
        Val(Object value, String type, int line, int col) {
            this.value = value; this.type = type; this.line = line; this.col = col;
        }
        @Override public String toString() { return String.valueOf(value); }
    }

    public static final class Nodes {
        public String name;
        public List<Object> args;
        public List<Object> nodes;
        public Object Left;
        public Object Op;
        public Object Right;
        public int line, col, level;   // ตำแหน่งเริ่มต้น + ระดับ block
    }

    private void at(Nodes n, Tok t) {
        n.line = t.line; n.col = t.col; n.level = t.level;
    }

    // =========================================================================
    // Opers Functions
    // =========================================================================
    private Tok Current() {
        if (this.pc >= this.Tokens.size()) {
            return this.Tokens.get(this.Tokens.size() - 1);
        }
        return this.Tokens.get(this.pc);
    }

    private int Advance() {
        if (this.pc + 1 >= this.Tokens.size()) {
            return 1;
        }
        pc += 1;
        return 0;
    }

    private int Advance(int k) {
        if (this.pc + k >= this.Tokens.size()) {
            return 1;
        }
        pc += k;
        return 0;
    }

    private void consume() {
        if (this.pc < this.Tokens.size()) this.pc++;
    }

    private void fail() {
        if (this.pc > this.farPc) this.farPc = this.pc;
    }

    /** ตำแหน่ง token ปัจจุบัน {line, col} (0,0 ถ้ายังไม่มี token เช่นตอนโหลด rule) */
    private int[] pos() {
        if (this.Tokens == null || this.Tokens.isEmpty()) return new int[]{0, 0};
        Tok t = Current();
        return new int[]{t.line, t.col};
    }

    private void Warning(String name, String content) {
        int[] p = pos();
        String msg = "[Warning] (" + name + ") " + content + (p[0] > 0 ? " InLine " + p[0] + ":" + p[1] : "");
        Warnings.add(msg);
        if (WarningPrint_Enable) System.err.println(msg);
    }

    /** โยน MonoClipException ที่ตำแหน่ง token ปัจจุบัน (ไม่ return) */
    private void Error(String name, String content, int code) {
        int[] p = pos();
        throw new MonoClipException(name, content, p[0], p[1], code);
    }

    private static void configError(String msg) {
        throw new MonoClipException("MonoClip", msg, 0, 0, 1);
    }

    private static List<Object> grp(Object... a) {
        return new ArrayList<Object>(Arrays.asList(a));
    }

    // =========================================================================
    // Layout : endline / tab counter / NEWLINE-INDENT-DEDENT (ทำภาษาแบบ Python ได้)
    //
    //   "layout": {                       (วางที่ root หรือใน grammar ก็ได้)
    //     "mode": "indent",               // none | newline | indent
    //     "endline": "auto",              // วิธีแบ่งบรรทัดของ source: auto | lf | crlf | cr | สตริงใดๆ
    //     "tabSize": 4,
    //     "tabMode": "stop",              // stop = tab ปัดไป multiple ถัดไปของ tabSize (แบบ Python) | fixed = tab = tabSize เสมอ
    //     "indentUnit": 0,                // >0 = บังคับว่าการเยื้องต้องหารด้วยค่านี้ลงตัว
    //     "mixedTabs": "allow",           // error = ห้ามผสม tab กับ space ในการเยื้องบรรทัดเดียวกัน
    //     "lineBase": 1,                  // เลขบรรทัดแรกที่ lexer ให้มา (0 หรือ 1)
    //     "brackets": [["(",")"],["[","]"],["{","}"]],   // ภายในวงเล็บไม่สร้าง NEWLINE/INDENT/DEDENT
    //     "continuation": "\\",           // token ที่ท้ายบรรทัด = ต่อบรรทัด ("" = ปิด)
    //     "separators": [";"],            // ตัวขั้นประโยค -> แปลงเป็น NEWLINE (ตัวที่อยู่ท้ายบรรทัดถูกทิ้ง)
    //     "terminators": [],              // ตัวจบบรรทัด -> แปลงเป็น NEWLINE เสมอ
    //     "multilineTypes": ["RAWSTRING"],// token ชนิดที่ value มีหลายบรรทัดจริง (นับ \n เพื่อหาบรรทัดจบ)
    //     "newlineType": "NEWLINE", "indentType": "INDENT", "dedentType": "DEDENT"
    //   }
    //
    //   token สังเคราะห์: NEWLINE (จบบรรทัดตรรกะ) / INDENT / DEDENT  ใช้ใน grammar ด้วย
    //   "@nl" "@indent" "@dedent" "type:newline" และ "@level>=1" (ตรวจระดับ block ปัจจุบัน)
    //   token ทุกตัวมี .indent (ความกว้าง) และ .level (ระดับ block)
    // =========================================================================
    private static final class Layout {
        String mode = "none";
        String endline = "auto";
        int tabSize = 4;
        boolean tabStop = true;
        int indentUnit = 0;
        boolean mixedError = false;
        int lineBase = 1;
        List<String[]> brackets = new ArrayList<>(Arrays.asList(
            new String[]{"(", ")"}, new String[]{"[", "]"}, new String[]{"{", "}"}));
        String continuation = "\\";
        Set<String> separators = new HashSet<>();
        Set<String> terminators = new HashSet<>();
        Set<String> multilineTypes = new HashSet<>();
        String nlType = "NEWLINE", inType = "INDENT", dedType = "DEDENT";
    }

    private static Set<String> strSet(Json.JsonObject o, String key, String... def) {
        Set<String> s = new HashSet<>();
        if (o != null && o.has(key)) {
            Object v = o.get(key);
            if (v instanceof Json.JsonArray) {
                for (Object x : (Json.JsonArray) v) s.add(String.valueOf(x));
            } else if (v != null) {
                s.add(String.valueOf(v));
            }
            return s;
        }
        s.addAll(Arrays.asList(def));
        return s;
    }

    private static String[] pairOf(Json.JsonObject o, String key, String a, String b) {
        if (o != null && o.has(key) && o.get(key) instanceof Json.JsonArray) {
            Json.JsonArray arr = o.getJsonArray(key);
            if (arr.size() == 2) return new String[]{String.valueOf(arr.get(0)), String.valueOf(arr.get(1))};
            configError("'" + key + "' must be [open, close]");
        }
        return new String[]{a, b};
    }

    private void loadLayout(Json.JsonObject main) {
        Json.JsonObject lo = main.has("layout") ? main.getJsonObject("layout")
                           : GrammarRule.has("layout") ? GrammarRule.getJsonObject("layout") : null;
        Layout L = new Layout();
        if (lo != null) {
            L.mode = lo.optString("mode", "none").toLowerCase();
            if (!L.mode.equals("none") && !L.mode.equals("newline") && !L.mode.equals("indent")) {
                configError("layout.mode must be none | newline | indent");
            }
            L.endline = lo.optString("endline", "auto");
            L.tabSize = Math.max(1, lo.optInt("tabSize", 4));
            L.tabStop = !lo.optString("tabMode", "stop").equalsIgnoreCase("fixed");
            L.indentUnit = lo.optInt("indentUnit", 0);
            L.mixedError = lo.optString("mixedTabs", "allow").equalsIgnoreCase("error");
            L.lineBase = lo.optInt("lineBase", 1);
            L.continuation = lo.optString("continuation", "\\");
            L.separators = strSet(lo, "separators");
            L.terminators = strSet(lo, "terminators");
            for (String t : strSet(lo, "multilineTypes")) L.multilineTypes.add(t.toUpperCase());
            L.nlType = lo.optString("newlineType", "NEWLINE").toUpperCase();
            L.inType = lo.optString("indentType", "INDENT").toUpperCase();
            L.dedType = lo.optString("dedentType", "DEDENT").toUpperCase();
            if (lo.has("brackets") && lo.get("brackets") instanceof Json.JsonArray) {
                L.brackets.clear();
                for (Object b : lo.getJsonArray("brackets")) {
                    if (!(b instanceof Json.JsonArray) || ((Json.JsonArray) b).size() != 2)
                        configError("layout.brackets must be [[open, close], ...]");
                    Json.JsonArray ba = (Json.JsonArray) b;
                    L.brackets.add(new String[]{String.valueOf(ba.get(0)), String.valueOf(ba.get(1))});
                }
            }
        }
        this.layout = L;
    }

    private String[] splitLines(String code) {
        String e = layout.endline;
        String re;
        switch (e.toLowerCase()) {
            case "auto": re = "\r\n|\r|\n"; break;
            case "lf":   re = "\n"; break;
            case "crlf": re = "\r\n"; break;
            case "cr":   re = "\r"; break;
            default:     re = Pattern.quote(e);
        }
        return Pattern.compile(re).split(code, -1);
    }

    private void layoutError(String msg, Tok t) {
        throw new MonoClipException("IndentationError", msg, t.line, t.col, 1);
    }

    private int indentOf(String[] lines, Tok t) {
        Layout L = layout;
        int idx = t.line - L.lineBase;
        if (idx < 0 || idx >= lines.length) return Math.max(0, t.col - 1);
        String s = lines[idx];
        int w = 0;
        boolean tab = false, sp = false;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == ' ') { w++; sp = true; }
            else if (ch == '\t') {
                w = L.tabStop ? ((w / L.tabSize) + 1) * L.tabSize : w + L.tabSize;
                tab = true;
            }
            else if (ch == '\f') w = 0;
            else break;
        }
        if (tab && sp && L.mixedError) layoutError("inconsistent use of tabs and spaces in indentation", t);
        return w;
    }

    private int endLineOf(Tok t) {
        int n = t.line;
        if (layout.multilineTypes.contains(t.type)) {
            for (int i = 0; i < t.value.length(); i++) if (t.value.charAt(i) == '\n') n++;
        }
        return n;
    }

    private boolean isLastOnLine(List<Tok> in, int i) {
        if (i + 1 >= in.size()) return true;
        Tok nx = in.get(i + 1);
        return typeIs(nx, "eof") || nx.line > endLineOf(in.get(i));
    }

    private Tok mk(String type, String value, int line, int col, int level) {
        Tok t = new Tok(type, value, line, col);
        t.synthetic = true;
        t.level = level;
        return t;
    }

    /** แทรก NEWLINE / INDENT / DEDENT ตาม layout (mode none = ไม่แตะ) */
    private List<Tok> applyLayout(List<Tok> in, String code) {
        Layout L = this.layout;
        if (L.mode.equals("none")) return in;
        boolean ind = L.mode.equals("indent");
        String[] lines = splitLines(code);

        List<Tok> out = new ArrayList<>(in.size() + 16);
        List<Integer> stack = new ArrayList<>();
        stack.add(0);
        Deque<String> closers = new ArrayDeque<>();
        boolean lineHas = false, pendingCont = false;
        int prevEnd = Integer.MIN_VALUE;
        int curIndent = 0;
        Tok last = null, eofTok = null;

        for (int i = 0; i < in.size(); i++) {
            Tok t = in.get(i);
            if (typeIs(t, "eof")) { eofTok = t; break; }
            boolean str = isString(t);
            String v = str ? null : t.value;

            // ---- เริ่มบรรทัดกายภาพใหม่ ----
            if (t.line > prevEnd) {
                if (closers.isEmpty() && !pendingCont) {
                    if (lineHas) {
                        out.add(mk(L.nlType, "\n", last.line, last.col + last.value.length(), stack.size() - 1));
                        lineHas = false;
                    }
                    if (ind) {
                        int w = indentOf(lines, t);
                        if (L.indentUnit > 0 && w % L.indentUnit != 0) {
                            layoutError("indentation must be a multiple of " + L.indentUnit, t);
                        }
                        int top = stack.get(stack.size() - 1);
                        if (w > top) {
                            stack.add(w);
                            out.add(mk(L.inType, String.valueOf(w), t.line, t.col, stack.size() - 1));
                        } else if (w < top) {
                            while (stack.get(stack.size() - 1) > w) {
                                stack.remove(stack.size() - 1);
                                out.add(mk(L.dedType, String.valueOf(stack.get(stack.size() - 1)),
                                           t.line, t.col, stack.size() - 1));
                            }
                            if (stack.get(stack.size() - 1) != w) {
                                layoutError("unindent does not match any outer indentation level", t);
                            }
                        }
                        curIndent = w;
                    }
                }
                pendingCont = false;
            }

            // ---- ต่อบรรทัด ----
            if (v != null && !L.continuation.isEmpty() && v.equals(L.continuation) && isLastOnLine(in, i)) {
                pendingCont = true;
                prevEnd = t.line;
                continue;
            }

            // ---- ตัวขั้น / ตัวจบ ----
            if (v != null && closers.isEmpty() && (L.separators.contains(v) || L.terminators.contains(v))) {
                boolean trailing = isLastOnLine(in, i);
                prevEnd = endLineOf(t);
                if (L.separators.contains(v) && !L.terminators.contains(v) && trailing) continue;
                if (lineHas) {
                    out.add(mk(L.nlType, v, t.line, t.col, stack.size() - 1));
                    lineHas = false;
                }
                continue;
            }

            t.level = stack.size() - 1;
            t.indent = curIndent;
            out.add(t);
            last = t;
            lineHas = true;

            if (v != null) {
                if (!closers.isEmpty() && v.equals(closers.peek())) {
                    closers.pop();
                } else {
                    for (String[] b : L.brackets) {
                        if (v.equals(b[0])) { closers.push(b[1]); break; }
                    }
                }
            }
            prevEnd = endLineOf(t);
        }

        if (lineHas && last != null) {
            out.add(mk(L.nlType, "\n", last.line, last.col + last.value.length(), stack.size() - 1));
        }
        int endLine = last != null ? last.line : 1;
        while (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            out.add(mk(L.dedType, String.valueOf(stack.get(stack.size() - 1)), endLine, 0, stack.size() - 1));
        }
        if (eofTok == null) eofTok = new Tok("EOF", "", endLine, 0);
        eofTok.level = 0;
        out.add(eofTok);
        return out;
    }

    // =========================================================================
    // Token Type Helper
    //   ชนิด token ทั้งหมดตั้งค่าได้ที่ grammar.tokenTypes ใน JSON
    // =========================================================================
    private static final String[][] DEFAULT_TYPES = {
        {"string",  "STRING", "RAWSTRING", "STR"},
        {"number",  "NUMBER", "INT", "INTEGER", "FLOAT", "DOUBLE", "REAL", "DEC", "HEX", "BIN", "OCT"},
        {"int",     "INT", "INTEGER", "HEX", "BIN", "OCT"},
        {"float",   "FLOAT", "DOUBLE", "REAL"},
        {"ident",   "IDENT", "IDENTIFIER", "ID", "NAME"},
        {"keyword", "KEYWORD", "KW"},
        {"symbol",  "SYMBOL", "OP", "OPERATOR", "PUNCT", "PUNCTUATION"},
        {"bool",    "BOOL", "BOOLEAN", "TRUE", "FALSE"},
        {"null",    "NULL", "NIL", "NONE"},
        {"eof",     "EOF"}
    };

    private static final Pattern NUM_RE = Pattern.compile(
        "^[+-]?(0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?)$");
    private static final Pattern INT_RE = Pattern.compile(
        "^[+-]?(0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*)$");

    private boolean typeIs(Tok tok, String kind) {
        Set<String> s = TypeMap.get(kind);
        return s != null && s.contains(tok.type);
    }

    private boolean isString(Tok tok) {
        return typeIs(tok, "string");
    }

    /** token จริง (ไม่ใช่สตริง ไม่ใช่ token สังเคราะห์) -> ใช้เทียบ "ค่า" ได้ */
    private boolean plain(Tok tok) {
        return !tok.synthetic && !isString(tok);
    }

    private boolean atEOF() {
        return this.pc >= this.Tokens.size() || typeIs(this.Current(), "eof");
    }

    private boolean curIs(String kind) {
        return this.pc < this.Tokens.size() && typeIs(this.Tokens.get(this.pc), kind);
    }

    private boolean isOp(String text) {
        if (atEOF()) return false;
        Tok t = Current();
        return plain(t) && t.value.equals(text);
    }

    private boolean isNumberTok(Tok tok, String v, boolean isStr) {
        return typeIs(tok, "number")
            || (!isStr && !tok.synthetic && !typeIs(tok, "ident") && !typeIs(tok, "symbol") && NUM_RE.matcher(v).matches());
    }

    private boolean isKeywordValue(String v, String group) {
        String w = KeywordIgnoreCase ? v.toLowerCase() : v;
        if (group == null) return KeywordAll.contains(w);
        Set<String> g = KeywordGroups.get(group);
        if (g != null) return g.contains(w);
        return (KeywordIgnoreCase ? group.toLowerCase() : group).equals(w);
    }

    private String describe(Tok t) {
        if (typeIs(t, "eof")) return "end of file";
        if (t.synthetic) {
            if (typeIs(t, "newline")) return "end of line";
            if (typeIs(t, "indent")) return "indent";
            if (typeIs(t, "dedent")) return "dedent";
        }
        return "token '" + t.value + "'";
    }

    /**
     * ตรวจ token ตาม spec
     *  kind สำเร็จรูป : any token string number int float ident keyword symbol bool null newline indent dedent eof
     *  kind ที่ประกาศใน tokenTypes : ชื่ออะไรก็ได้
     *  type:NAME      : ตรวจ type ตรงตัว
     *  kw:Group|word  : keyword จาก JSON
     *  [a-zA-Z0-9_]   : token ทั้งก้อนต้องอยู่ใน class (รองรับ ^ ranges \d \w \s)
     *  re:REGEX       : regex เต็มก้อน
     *  sym:+-*\/=     : token ประกอบจากอักขระ symbol ที่ระบุเท่านั้น
     *  class:Name / $Name : custom class จาก grammar.classes
     *  lit:text / ilit:text : literal / literal ไม่สนตัวพิมพ์
     */
    private boolean tokenTest(Tok tok, String spec) {
        String sp = spec.trim();
        String low = sp.toLowerCase();
        String v = tok.value;
        boolean isStr = isString(tok);
        boolean vl = isStr || tok.synthetic;      // ไม่มี "ค่า" ให้เทียบ

        switch (low) {
            case "any": case "token": return true;
            case "string":  return isStr;
            case "number":  return isNumberTok(tok, v, isStr);
            case "int":     return typeIs(tok, "int") || (isNumberTok(tok, v, isStr) && INT_RE.matcher(v).matches());
            case "float":   return typeIs(tok, "float") || (isNumberTok(tok, v, isStr) && !INT_RE.matcher(v).matches());
            case "ident":   return !vl && typeIs(tok, "ident") && !isKeywordValue(v, null);
            case "keyword": return !vl && (typeIs(tok, "keyword") || isKeywordValue(v, null));
            case "symbol":  return !vl && typeIs(tok, "symbol");
            case "bool":    return !vl && (typeIs(tok, "bool") || v.equals("true") || v.equals("false"));
            case "null":    return !vl && (typeIs(tok, "null") || v.equals("null"));
            default: break;
        }

        if (low.startsWith("type:")) {
            String t = sp.substring(5).trim();
            return tok.type.equals(t.toUpperCase()) || tokenTest(tok, t);
        }

        Boolean r = valueTest(vl ? null : v, sp, 0);
        if (r != null) return r;

        if (typeIs(tok, low)) return true;
        if (TypeMap.containsKey(low)) return false;
        return tok.type.equals(sp.toUpperCase());
    }

    private Boolean valueTest(String v, String sp, int d) {
        boolean cls = isCharClass(sp);
        String low = sp.toLowerCase();
        boolean isValueSpec = cls || low.startsWith("re:") || low.startsWith("sym:") || low.startsWith("class:")
            || low.startsWith("kw:") || low.startsWith("lit:") || low.startsWith("ilit:")
            || (sp.startsWith("$") && sp.length() > 1);
        if (!isValueSpec) return null;
        if (v == null) return false;

        if (cls) return charClassAll(v, sp);
        if (low.startsWith("re:")) return regex(sp.substring(3)).matcher(v).matches();
        if (low.startsWith("sym:")) {
            String set = sp.substring(4);
            if (v.isEmpty()) return false;
            for (char ch : v.toCharArray()) if (set.indexOf(ch) < 0) return false;
            return true;
        }
        if (low.startsWith("kw:"))   return isKeywordValue(v, sp.substring(3));
        if (low.startsWith("lit:"))  return v.equals(sp.substring(4));
        if (low.startsWith("ilit:")) return v.equalsIgnoreCase(sp.substring(5));
        String name = sp.startsWith("$") ? sp.substring(1) : sp.substring(6);
        return classTest(v, name, d + 1);
    }

    private boolean classTest(String v, String name, int d) {
        if (d > 16) Error("MonoClip", "Class recursion too deep '" + name + "'", 1);
        if (ClassRule == null || !ClassRule.has(name)) {
            Error("MonoClip", "Unknown class '" + name + "'", 1);
            return false;
        }
        Object c = ClassRule.get(name);
        if (c instanceof String) {
            Boolean r = valueTest(v, (String) c, d);
            return r != null ? r : v.equals(c);
        }
        if (c instanceof Json.JsonArray) {
            for (Object x : (Json.JsonArray) c) if (v.equals(String.valueOf(x))) return true;
            return false;
        }
        if (c instanceof Json.JsonObject) {
            Json.JsonObject o = (Json.JsonObject) c;
            if (o.has("re")) return regex(o.getString("re")).matcher(v).matches();
            if (o.has("first")) {
                if (v.isEmpty()) return false;
                String first = o.getString("first");
                String rest = o.optString("rest", first);
                if (!charClassAll(v.substring(0, 1), first)) return false;
                return v.length() == 1 || charClassAll(v.substring(1), rest);
            }
        }
        Error("MonoClip", "Bad class definition '" + name + "'", 1);
        return false;
    }

    private Pattern regex(String re) {
        Pattern p = regexCache.get(re);
        if (p == null) {
            try {
                p = Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                Error("MonoClip", "Bad regex '" + re + "'", 1);
            }
            regexCache.put(re, p);
        }
        return p;
    }

    // ---- char class แบบ peglib ----
    private static final class CharSet {
        boolean neg;
        final List<int[]> ranges = new ArrayList<>();
        boolean has(int c) {
            boolean m = false;
            for (int[] r : ranges) if (c >= r[0] && c <= r[1]) { m = true; break; }
            return m != neg;
        }
    }

    private static boolean isCharClass(String s) {
        return s.length() >= 3 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']';
    }

    private CharSet compileCharSet(String cls) {
        CharSet cs = charSetCache.get(cls);
        if (cs != null) return cs;
        if (!isCharClass(cls)) {
            Error("MonoClip", "Bad char class '" + cls + "'", 1);
        }
        cs = new CharSet();
        int i = 1, end = cls.length() - 1;
        if (i < end && cls.charAt(i) == '^') { cs.neg = true; i++; }
        while (i < end) {
            int a = cls.charAt(i++);
            if (a == '\\' && i < end) {
                char e = cls.charAt(i++);
                switch (e) {
                    case 'd': cs.ranges.add(new int[]{'0', '9'}); continue;
                    case 'w':
                        cs.ranges.add(new int[]{'a', 'z'}); cs.ranges.add(new int[]{'A', 'Z'});
                        cs.ranges.add(new int[]{'0', '9'}); cs.ranges.add(new int[]{'_', '_'});
                        continue;
                    case 's':
                        for (char w : new char[]{' ', '\t', '\n', '\r'}) cs.ranges.add(new int[]{w, w});
                        continue;
                    case 'n': a = '\n'; break;
                    case 't': a = '\t'; break;
                    case 'r': a = '\r'; break;
                    default:  a = e;
                }
            }
            int b = a;
            if (i + 1 < end && cls.charAt(i) == '-') {
                i++;
                b = cls.charAt(i++);
                if (b == '\\' && i < end) b = cls.charAt(i++);
            }
            cs.ranges.add(new int[]{a, b});
        }
        charSetCache.put(cls, cs);
        return cs;
    }

    private boolean charClassAll(String v, String cls) {
        if (v.isEmpty()) return false;
        CharSet cs = compileCharSet(cls);
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            if (!cs.has(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    // =========================================================================
    // Collect : แปลงค่า token ตามชนิด + ห่อ Val (type, line, col)
    // =========================================================================
    private Object parseNumber(String v) {
        String t = v.replace("_", "");
        try {
            if (t.matches("[+-]?0[xX][0-9a-fA-F]+")) return Long.parseLong(t.replaceFirst("0[xX]", ""), 16);
            if (t.matches("[+-]?0[bB][01]+"))        return Long.parseLong(t.replaceFirst("0[bB]", ""), 2);
            if (t.matches("[+-]?0[oO][0-7]+"))       return Long.parseLong(t.replaceFirst("0[oO]", ""), 8);
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return Double.parseDouble(t); }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object convertToken(Tok tok, String spec) {
        String v = tok.value;
        switch (spec.trim().toLowerCase()) {
            case "token": return tok;
            case "int": case "number": {
                Object n = parseNumber(v);
                return n != null ? n : v;
            }
            case "float": {
                Object n = parseNumber(v);
                return n instanceof Number ? (Object) ((Number) n).doubleValue() : v;
            }
            case "bool":
                if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
                if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
                return v;
            case "any":
                if (!isString(tok) && isNumberTok(tok, v, false)) {
                    Object n = parseNumber(v);
                    if (n != null) return n;
                }
                return v;
            default:
                return v;
        }
    }

    /** ชนิดข้อมูลของ token (ใช้ใส่ Val.type) */
    private String kindOf(Tok t) {
        String v = t.value;
        if (t.synthetic) return t.type.toLowerCase();
        if (isString(t)) return "string";
        if (isNumberTok(t, v, false)) return INT_RE.matcher(v).matches() ? "int" : "float";
        if (typeIs(t, "bool") || v.equals("true") || v.equals("false")) return "bool";
        if (typeIs(t, "null")) return "null";
        if (typeIs(t, "keyword") || isKeywordValue(v, null)) return "keyword";
        if (typeIs(t, "ident")) return "ident";
        if (typeIs(t, "symbol")) return "symbol";
        return t.type.toLowerCase();
    }

    private String dataType(Object v) {
        if (v == null) return "null";
        if (v instanceof Long || v instanceof Integer) return "int";
        if (v instanceof Double || v instanceof Float) return "float";
        if (v instanceof Boolean) return "bool";
        if (v instanceof String) return "string";
        if (v instanceof Json.JsonArray) return "array";
        if (v instanceof Json.JsonObject) return "object";
        if (v instanceof List) return "list";
        if (v instanceof Map) return "map";
        if (v instanceof Nodes) return "node";
        if (v instanceof Tok) return "token";
        return v.getClass().getSimpleName().toLowerCase();
    }

    // =========================================================================
    // Auto collect : list / array / map / object / tuple / value / json / code / raw / block
    //
    //   collect:list      [a, b, c]        -> List        (grammar.auto.list   default [ ])
    //   collect:array     [a, b, c]        -> Json.JsonArray
    //   collect:map       {k: v, ...}      -> LinkedHashMap (grammar.auto.map  default { })
    //   collect:object    {"k": v, ...}    -> Json.JsonObject
    //   collect:tuple     (a, b)           -> List        (grammar.auto.tuple default ( ))
    //   collect:value     ค่าอัตโนมัติ: เลข/สตริง/bool/null/ident/list/map/tuple
    //   collect:json      JSON จาก token ตรงๆ หรือจาก token สตริงที่เป็นข้อความ JSON -> JsonObject/JsonArray/...
    //                     (collect:json:text บังคับให้ตีความ token สตริงเป็นข้อความ JSON)
    //   collect:code      { stmt stmt ... } -> Nodes<Code>{nodes=statements}  (collect:code:Name ใช้ grammar.blocks.Name)
    //   collect:raw       { ... } เก็บเป็นข้อความดิบ (ต่อ token ด้วย auto.rawJoin) ไม่ parse  (collect:raw:Name)
    //   collect:block     body แบบ Python: NEWLINE INDENT stmts DEDENT หรือ statement เดียวบรรทัดเดียวกัน -> Nodes<Block>
    //
    //   grammar.auto   : {"list":["[","]"],"map":["{","}"],"tuple":["(",")"],"code":["{","}"],
    //                     "sep":[","],"pair":[":"],"trailingComma":true,"rawJoin":" "}
    //   grammar.blocks : {"Do": {"open":"do","close":"end"}}
    // =========================================================================
    private static final Set<String> COMPOSITE = new HashSet<>(Arrays.asList(
        "list", "array", "map", "object", "tuple", "value", "json", "code", "raw", "block"));

    private void skipLayout() {
        while (!atEOF() && (curIs("newline") || curIs("indent") || curIs("dedent"))) consume();
    }

    private boolean curIsSep() {
        if (atEOF()) return false;
        Tok t = Current();
        return plain(t) && autoSep.contains(t.value);
    }

    private static Object negate(Object n) {
        if (n instanceof Long) return -((Long) n);
        return -((Number) n).doubleValue();
    }

    private Object autoValue(boolean json, int depth) {
        if (depth > MAX_RULE_DEPTH) Error("MonoClip", "Value nesting too deep", 1);
        skipLayout();
        if (atEOF()) return NOPE;

        if (isOp(autoList[0]))             return json ? autoJsonArray(depth) : autoItemsOrNope(autoList, false, depth);
        if (isOp(autoMap[0]))              return autoMapValue(json, depth);
        if (!json && isOp(autoTuple[0]))   return autoItemsOrNope(autoTuple, false, depth);

        Tok t = Current();
        if (t.synthetic) return NOPE;
        String v = t.value;

        if (isString(t)) { consume(); return v; }

        if ((v.equals("-") || v.equals("+")) && this.pc + 1 < Tokens.size()) {
            Tok n = Tokens.get(this.pc + 1);
            if (plain(n) && isNumberTok(n, n.value, false)) {
                Object num = parseNumber(n.value);
                if (num != null) {
                    consume(); consume();
                    return v.equals("-") ? negate(num) : num;
                }
            }
        }
        if (isNumberTok(t, v, false)) {
            Object num = parseNumber(v);
            if (num != null) { consume(); return num; }
        }
        switch (v) {
            case "true":  consume(); return Boolean.TRUE;
            case "false": consume(); return Boolean.FALSE;
            case "null":  consume(); return null;
            default: break;
        }
        if (!json) {
            switch (v) {
                case "True":  consume(); return Boolean.TRUE;
                case "False": consume(); return Boolean.FALSE;
                case "None": case "nil": consume(); return null;
                default: break;
            }
            if (typeIs(t, "ident") || typeIs(t, "keyword") || isKeywordValue(v, null)) {
                consume();
                return v;
            }
        }
        return NOPE;
    }

    /** open item (sep item)* [sep] close -> List หรือ NOPE */
    private Object autoItemsOrNope(String[] br, boolean json, int depth) {
        int start = this.pc;
        consume();
        List<Object> items = new ArrayList<>();
        skipLayout();
        if (isOp(br[1])) { consume(); return items; }
        while (true) {
            Object x = autoValue(json, depth + 1);
            if (x == NOPE) { this.pc = start; return NOPE; }
            items.add(x);
            skipLayout();
            if (isOp(br[1])) { consume(); return items; }
            if (curIsSep()) {
                consume();
                skipLayout();
                if (autoTrailing && isOp(br[1])) { consume(); return items; }
                continue;
            }
            this.pc = start;
            return NOPE;
        }
    }

    private Object autoJsonArray(int depth) {
        Object r = autoItemsOrNope(autoList, true, depth);
        if (r == NOPE) return NOPE;
        Json.JsonArray arr = new Json.JsonArray();
        for (Object x : (List<?>) r) arr.add(x);
        return arr;
    }

    private Object autoMapValue(boolean json, int depth) {
        int start = this.pc;
        consume();
        Map<String, Object> m = new LinkedHashMap<>();
        skipLayout();
        if (isOp(autoMap[1])) {
            consume();
            return json ? (Object) new Json.JsonObject() : (Object) m;
        }
        while (true) {
            skipLayout();
            if (atEOF()) { this.pc = start; return NOPE; }
            Tok kt = Current();
            String key;
            if (isString(kt)) {
                key = kt.value;
            } else if (!json && plain(kt)
                       && (typeIs(kt, "ident") || typeIs(kt, "keyword") || isNumberTok(kt, kt.value, false))) {
                key = kt.value;
            } else {
                this.pc = start;
                return NOPE;
            }
            consume();
            skipLayout();
            if (atEOF() || !plain(Current()) || !autoPair.contains(Current().value)) {
                this.pc = start;
                return NOPE;
            }
            consume();
            Object v = autoValue(json, depth + 1);
            if (v == NOPE) { this.pc = start; return NOPE; }
            m.put(key, v);
            skipLayout();
            if (isOp(autoMap[1])) { consume(); break; }
            if (curIsSep()) {
                consume();
                skipLayout();
                if (autoTrailing && isOp(autoMap[1])) { consume(); break; }
                continue;
            }
            this.pc = start;
            return NOPE;
        }
        if (json) {
            Json.JsonObject jo = new Json.JsonObject();
            for (Map.Entry<String, Object> e : m.entrySet()) jo.put(e.getKey(), e.getValue());
            return jo;
        }
        return m;
    }

    private String[] blockDelims(String name) {
        if (name == null || name.isEmpty()) return codeB;
        String[] d = blocks.get(name);
        if (d == null) Error("MonoClip", "Unknown block '" + name + "' (grammar.blocks)", 1);
        return d;
    }

    private Nodes parseCodeBlock(String open, String close) {
        int start = this.pc;
        if (!isOp(open)) { fail(); return null; }
        Tok st = Current();
        consume();
        List<Object> stmts = new ArrayList<>();
        while (true) {
            while (curIs("newline")) consume();
            if (isOp(close)) { consume(); break; }
            if (atEOF()) { this.pc = start; fail(); return null; }
            int b = this.pc;
            Nodes s = Statement();
            if (s == null || this.pc == b) { this.pc = start; return null; }
            stmts.add(s);
        }
        Nodes n = new Nodes();
        n.name = "Code";
        n.nodes = stmts;
        at(n, st);
        return n;
    }

    private Nodes parseIndentBlock() {
        int start = this.pc;
        Tok st = Current();
        List<Object> stmts = new ArrayList<>();

        if (!curIs("newline")) {                       // statement เดียวบนบรรทัดเดียวกัน: if x: print 1
            Nodes s = Statement();
            if (s == null) { this.pc = start; return null; }
            stmts.add(s);
        } else {
            consume();
            while (curIs("newline")) consume();
            if (!curIs("indent")) { this.pc = start; fail(); return null; }
            st = Current();
            consume();
            while (!curIs("dedent")) {
                while (curIs("newline")) consume();
                if (curIs("dedent")) break;
                if (atEOF()) { this.pc = start; fail(); return null; }
                int b = this.pc;
                Nodes s = Statement();
                if (s == null || this.pc == b) { this.pc = start; return null; }
                stmts.add(s);
            }
            consume();                                 // DEDENT
        }
        Nodes n = new Nodes();
        n.name = "Block";
        n.nodes = stmts;
        at(n, st);
        return n;
    }

    private String rawBlock(String open, String close) {
        int start = this.pc;
        if (!isOp(open)) { fail(); return null; }
        consume();
        int depth = 1;
        StringBuilder sb = new StringBuilder();
        boolean atLineStart = true;
        while (!atEOF()) {
            Tok t = Current();
            if (isOp(close)) {
                depth--;
                if (depth == 0) { consume(); return sb.toString(); }
            } else if (isOp(open)) {
                depth++;
            }
            if (t.synthetic) {
                if (typeIs(t, "newline")) { sb.append('\n'); atLineStart = true; }
            } else {
                if (!atLineStart) sb.append(rawJoin);
                sb.append(isString(t) ? "\"" + t.value + "\"" : t.value);
                atLineStart = false;
            }
            consume();
        }
        this.pc = start;
        fail();
        return null;
    }

    /** @return null = spec ไม่ใช่ composite, true/false = ผล match */
    private Boolean matchComposite(String spec, List<Object> out, boolean capture, boolean meta) {
        String sp = spec.trim();
        String low = sp.toLowerCase();
        String head = low, arg = null;
        int c = sp.indexOf(':');
        if (c >= 0) { head = low.substring(0, c); arg = sp.substring(c + 1); }
        if (!COMPOSITE.contains(head)) return null;

        if (atEOF()) { fail(); return false; }
        int start = this.pc;
        Tok st = Current();
        Object val = NOPE;
        String type = head;

        switch (head) {
            case "list":   val = isOp(autoList[0]) ? autoItemsOrNope(autoList, false, 0) : NOPE; break;
            case "tuple":  val = isOp(autoTuple[0]) ? autoItemsOrNope(autoTuple, false, 0) : NOPE; break;
            case "array":  val = isOp(autoList[0]) ? autoJsonArray(0) : NOPE; break;
            case "map":    val = isOp(autoMap[0]) ? autoMapValue(false, 0) : NOPE; break;
            case "object": val = isOp(autoMap[0]) ? autoMapValue(true, 0) : NOPE; break;
            case "value":  val = autoValue(false, 0); type = null; break;
            case "json": {
                type = null;
                boolean text = "text".equalsIgnoreCase(arg);
                if (isString(st) && (text || st.value.trim().startsWith("{") || st.value.trim().startsWith("["))) {
                    try {
                        val = Json.parse(st.value);
                        consume();
                    } catch (MonoClipException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw new MonoClipException("JsonError", String.valueOf(e.getMessage()), st.line, st.col, 1, e);
                    }
                } else if (!text) {
                    val = autoValue(true, 0);
                }
                break;
            }
            case "code": {
                String[] d = blockDelims(arg);
                val = parseCodeBlock(d[0], d[1]);
                if (val == null) val = NOPE;
                break;
            }
            case "raw": {
                String[] d = blockDelims(arg);
                val = rawBlock(d[0], d[1]);
                if (val == null) val = NOPE;
                break;
            }
            case "block": {
                val = parseIndentBlock();
                if (val == null) val = NOPE;
                break;
            }
            default: break;
        }

        if (val == NOPE) {
            this.pc = start;
            fail();
            return false;
        }
        if (this.pc == start) {                        // ไม่ได้กิน token (กัน loop)
            fail();
            return false;
        }
        if (capture && out != null) {
            Object v = val;
            if (meta || collectMeta) v = new Val(v, type != null ? type : dataType(val), st.line, st.col);
            out.add(v);
        }
        return true;
    }

    // =========================================================================
    // Grammar Matching Functions
    //
    //   Element ใน sequence มี 2 ชนิด:  String  |  Array (group)
    //
    //   String:
    //     literal            "print" "(" ";"                  (ไม่เก็บค่า / ไม่ match token สตริง)
    //     lit:x / ilit:x     literal แบบบังคับ / ไม่สนตัวพิมพ์
    //     %Rule              อ้างอิง rule (pratt -> exprs -> statements)   %Statement = statement ใดๆ (ซ้อนใน block ได้)
    //     collect:SPEC       เก็บค่า token ตาม SPEC (+ composite: list map json code block ... ดูด้านบน)
    //     val:SPEC           เหมือน collect แต่ห่อเป็น Val{value,type,line,col}
    //     type:SPEC          ตรวจแต่ไม่เก็บ
    //     kw:Group  [a-z_]  re:REGEX  sym:+-*  class:Name  $Name
    //     @eof @any @nl @indent @dedent          token พิเศษ / สังเคราะห์
    //     @level>=N  (==  !=  >=  <=  >  <)      ตรวจระดับ block ของ token ปัจจุบัน (ไม่กิน token)
    //
    //   peglib shorthand (ใช้กับ %Rule $Name collect: val: type: kw: class: [..] @nl @indent @dedent @any @eof):
    //     X*  X+  X?   &X  !X  ~X
    //
    //   Group (Array):
    //     choice/oneof  seq  opt/optional  many/star  some/plus  rep(min,max,...)
    //     sep  sep0  sept(ยอม trailing)  peek  not  ignore  cap  until  any  eof
    // =========================================================================
    private static final int MAX_RULE_DEPTH = 256;

    private static List<Object> asList(Object o) {
        if (o instanceof Json.JsonArray) {
            List<Object> l = new ArrayList<>();
            for (Object x : (Json.JsonArray) o) l.add(x);
            return l;
        }
        if (o instanceof List) {
            return new ArrayList<Object>((List<?>) o);
        }
        return null;
    }

    private static void packInto(List<Object> tmp, List<Object> dst) {
        if (tmp.size() == 1) dst.add(tmp.get(0));
        else if (tmp.size() > 1) dst.add(tmp);
    }

    private List<Object> matchSequence(Json.JsonArray seq, int depth) {
        if (depth > MAX_RULE_DEPTH) {
            Error("MonoClip", "Grammar recursion too deep", 1);
        }
        List<Object> out = new ArrayList<>();
        if (!matchItems(asList(seq), 0, out, depth)) return null;
        return out;
    }

    private boolean matchItems(List<Object> items, int from, List<Object> out, int depth) {
        int start = this.pc;
        List<Object> tmp = new ArrayList<>();
        for (int i = from; i < items.size(); i++) {
            if (!matchElement(items.get(i), tmp, depth)) {
                this.pc = start;
                return false;
            }
        }
        out.addAll(tmp);
        return true;
    }

    private boolean matchElement(Object el, List<Object> out, int depth) {
        List<Object> group = asList(el);
        if (group != null) {
            return matchGroup(group, out, depth);
        }

        String s = String.valueOf(el);

        List<Object> sh = shorthand(s);
        if (sh != null) return matchGroup(sh, out, depth);

        if (s.startsWith("@") && s.length() > 1) return matchAt(s.substring(1));

        if (s.startsWith("%") && isIdentFrom(s, 1)) {
            return matchRuleRef(s.substring(1), out, depth);
        }
        if (s.startsWith("$") && isIdentFrom(s, 1)) {
            return matchSpec(s, out, false, false);
        }
        if (s.startsWith("collect:")) {
            return matchSpec(s.substring("collect:".length()), out, true, false);
        }
        if (s.startsWith("val:")) {
            return matchSpec(s.substring("val:".length()), out, true, true);
        }
        if (s.startsWith("type:")) {
            return matchSpec(s.substring("type:".length()), out, false, false);
        }
        if (s.startsWith("kw:") || s.startsWith("class:") || s.startsWith("re:") || s.startsWith("sym:") || isCharClass(s)) {
            return matchSpec(s, out, false, false);
        }
        if (s.startsWith("lit:"))  return matchLiteral(s.substring(4), false);
        if (s.startsWith("ilit:")) return matchLiteral(s.substring(5), true);

        return matchLiteral(s, false);
    }

    private static boolean isIdentFrom(String s, int from) {
        if (from >= s.length()) return false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static final Pattern LEVEL_RE = Pattern.compile("^level(==|!=|>=|<=|>|<)(\\d+)$");

    private boolean matchAt(String name) {
        String low = name.toLowerCase();
        switch (low) {
            case "eof": case "any": case "indent": case "dedent":
                return matchSpec(low, null, false, false);
            case "nl": case "newline":
                return matchSpec("newline", null, false, false);
            default: break;
        }
        Matcher m = LEVEL_RE.matcher(low);
        if (m.matches()) {
            int lv = Current().level, n = Integer.parseInt(m.group(2));
            boolean ok;
            switch (m.group(1)) {
                case "==": ok = lv == n; break;
                case "!=": ok = lv != n; break;
                case ">=": ok = lv >= n; break;
                case "<=": ok = lv <= n; break;
                case ">":  ok = lv > n; break;
                default:   ok = lv < n; break;
            }
            if (!ok) fail();
            return ok;
        }
        Error("MonoClip", "Unknown element '@" + name + "'", 1);
        return false;
    }

    // ---- peglib shorthand ----
    private List<Object> shorthand(String s) {
        if (s.length() < 2) return null;
        if (shorthandCache.containsKey(s)) return shorthandCache.get(s);
        List<Object> r = buildShorthand(s);
        shorthandCache.put(s, r);
        return r;
    }

    private List<Object> buildShorthand(String s) {
        char c0 = s.charAt(0);
        if (c0 == '&' || c0 == '!' || c0 == '~') {
            Object inner = parsePeg(s.substring(1));
            if (inner == null) return null;
            return grp(c0 == '&' ? "peek" : c0 == '!' ? "not" : "ignore", inner);
        }
        Object r = parsePeg(s);
        return r instanceof List ? asList(r) : null;
    }

    private Object parsePeg(String b) {
        int n = b.length();
        if (n >= 2) {
            char last = b.charAt(n - 1);
            if (last == '*' || last == '+' || last == '?') {
                String base = b.substring(0, n - 1);
                if (isPostfixable(base)) {
                    return grp(last == '*' ? "many" : last == '+' ? "some" : "opt", base);
                }
            }
        }
        return isSpecForm(b) ? b : null;
    }

    private boolean isSpecForm(String b) {
        if (b.startsWith("%") || b.startsWith("$")) return isIdentFrom(b, 1);
        if (b.startsWith("@")) return b.length() > 1;
        return b.startsWith("collect:") || b.startsWith("val:") || b.startsWith("type:") || b.startsWith("kw:")
            || b.startsWith("class:") || b.startsWith("re:") || b.startsWith("sym:") || b.startsWith("lit:")
            || b.startsWith("ilit:") || isCharClass(b);
    }

    private boolean isPostfixable(String b) {
        if (b.startsWith("%") || b.startsWith("$")) return isIdentFrom(b, 1);
        if (b.startsWith("@")) {
            String n = b.substring(1).toLowerCase();
            return n.equals("nl") || n.equals("newline") || n.equals("indent") || n.equals("dedent")
                || n.equals("any") || n.equals("eof");
        }
        if (b.startsWith("collect:") || b.startsWith("val:")) {
            String r = b.substring(b.indexOf(':') + 1);
            return !r.isEmpty() && !r.startsWith("re:") && !r.startsWith("sym:");
        }
        return b.startsWith("type:") || b.startsWith("kw:") || b.startsWith("class:") || isCharClass(b);
    }

    // ---- group dispatcher ----
    private boolean matchGroup(List<Object> g, List<Object> out, int depth) {
        if (g.isEmpty()) {
            Error("MonoClip", "Empty group in grammar", 1);
            return false;
        }
        String head = String.valueOf(g.get(0));
        switch (head) {
            case "choice": case "oneof":              return matchChoice(g, out, depth);
            case "seq":                               return matchItems(g, 1, out, depth);
            case "opt": case "optional":              return matchOpt(g, out, depth);
            case "many": case "star":                 return matchRepeat(g, 1, out, depth, 0, -1);
            case "some": case "plus":                 return matchRepeat(g, 1, out, depth, 1, -1);
            case "rep":                               return matchRep(g, out, depth);
            case "sep":                               return matchSep(g, out, depth, 1, false);
            case "sep0":                              return matchSep(g, out, depth, 0, false);
            case "sept":                              return matchSep(g, out, depth, 0, true);
            case "peek":                              return matchLook(g, depth, false);
            case "not":                               return matchLook(g, depth, true);
            case "ignore":                            return matchItems(g, 1, new ArrayList<>(), depth);
            case "cap":                               return matchCap(g, out, depth);
            case "until":                             return matchUntil(g, out, depth);
            case "any":                               return matchSpec("any", null, false, false);
            case "eof":                               return matchSpec("eof", null, false, false);
            default:
                Error("MonoClip", "Unknown grammar group '" + head + "'", 1);
                return false;
        }
    }

    private boolean matchChoice(List<Object> g, List<Object> out, int depth) {
        int start = this.pc;
        for (int i = 1; i < g.size(); i++) {
            List<Object> tmp = new ArrayList<>();
            if (matchElement(g.get(i), tmp, depth)) {
                out.addAll(tmp);
                return true;
            }
            this.pc = start;
        }
        return false;
    }

    private boolean matchOpt(List<Object> g, List<Object> out, int depth) {
        matchItems(g, 1, out, depth);
        return true;
    }

    private boolean matchRepeat(List<Object> g, int from, List<Object> out, int depth, int min, int max) {
        int start = this.pc;
        List<Object> items = new ArrayList<>();
        int count = 0;
        while (max < 0 || count < max) {
            int before = this.pc;
            List<Object> tmp = new ArrayList<>();
            if (!matchItems(g, from, tmp, depth)) break;
            if (this.pc == before) break;
            packInto(tmp, items);
            count++;
        }
        if (count < min) {
            this.pc = start;
            return false;
        }
        out.add(items);
        return true;
    }

    private boolean matchRep(List<Object> g, List<Object> out, int depth) {
        if (g.size() < 4 || !(g.get(1) instanceof Number) || !(g.get(2) instanceof Number)) {
            Error("MonoClip", "'rep' needs [\"rep\", min, max, ...items]", 1);
            return false;
        }
        return matchRepeat(g, 3, out, depth, ((Number) g.get(1)).intValue(), ((Number) g.get(2)).intValue());
    }

    private boolean matchSep(List<Object> g, List<Object> out, int depth, int min, boolean trailing) {
        if (g.size() != 3) {
            Error("MonoClip", "'" + g.get(0) + "' needs exactly 2 arguments: [\"" + g.get(0) + "\", item, separator]", 1);
            return false;
        }
        Object item = g.get(1), sepEl = g.get(2);
        int start = this.pc;
        List<Object> items = new ArrayList<>();

        List<Object> first = new ArrayList<>();
        if (!matchElement(item, first, depth)) {
            this.pc = start;
            if (min > 0) return false;
            out.add(items);
            return true;
        }
        packInto(first, items);

        while (true) {
            int save = this.pc;
            List<Object> ignored = new ArrayList<>();
            List<Object> tmp = new ArrayList<>();
            if (matchElement(sepEl, ignored, depth) && matchElement(item, tmp, depth) && this.pc != save) {
                packInto(tmp, items);
            } else {
                this.pc = save;
                if (trailing) matchElement(sepEl, new ArrayList<>(), depth);
                break;
            }
        }
        out.add(items);
        return true;
    }

    private boolean matchLook(List<Object> g, int depth, boolean negate) {
        int start = this.pc;
        boolean ok = matchItems(g, 1, new ArrayList<>(), depth);
        this.pc = start;
        return negate ? !ok : ok;
    }

    private String rawText(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < Tokens.size(); i++) {
            if (!Tokens.get(i).synthetic) sb.append(Tokens.get(i).value);
        }
        return sb.toString();
    }

    private boolean matchCap(List<Object> g, List<Object> out, int depth) {
        int start = this.pc;
        if (!matchItems(g, 1, new ArrayList<>(), depth)) return false;
        out.add(rawText(start, this.pc));
        return true;
    }

    private boolean matchUntil(List<Object> g, List<Object> out, int depth) {
        List<Object> items = new ArrayList<>();
        while (!atEOF()) {
            int s = this.pc;
            boolean hit = matchItems(g, 1, new ArrayList<>(), depth);
            this.pc = s;
            if (hit) break;
            items.add(Current().value);
            consume();
        }
        out.add(items);
        return true;
    }

    private boolean matchLiteral(String text, boolean ignoreCase) {
        if (atEOF()) { fail(); return false; }
        Tok tok = this.Current();
        if (!plain(tok)) { fail(); return false; }
        String v = tok.value;
        if (!(ignoreCase ? text.equalsIgnoreCase(v) : text.equals(v))) { fail(); return false; }
        consume();
        return true;
    }

    private boolean matchSpec(String spec, List<Object> out, boolean capture, boolean meta) {
        Boolean comp = matchComposite(spec, out, capture, meta);
        if (comp != null) return comp;

        if (spec.trim().equalsIgnoreCase("eof")) {
            boolean ok = atEOF();
            if (!ok) fail();
            return ok;
        }
        if (atEOF()) { fail(); return false; }
        Tok tok = Current();
        if (!tokenTest(tok, spec)) { fail(); return false; }
        if (capture && out != null) {
            Object v = convertToken(tok, spec);
            if (meta || collectMeta) {
                String ty = v instanceof Long ? "int" : v instanceof Double ? "float"
                          : v instanceof Boolean ? "bool" : kindOf(tok);
                v = new Val(v, ty, tok.line, tok.col);
            }
            out.add(v);
        }
        consume();
        return true;
    }

    private boolean matchRuleRef(String name, List<Object> out, int depth) {
        if (depth > MAX_RULE_DEPTH) {
            Error("MonoClip", "Grammar recursion too deep", 1);
        }

        if (PrattRule != null && PrattRule.has(name)) {
            Object v = parseExpr(prattOf(name), 0, depth + 1);
            if (v == null) return false;
            out.add(v);
            return true;
        }

        Json.JsonArray rule = null;
        if (ExprRule != null && ExprRule.has(name))      rule = ExprRule.getJsonArray(name);
        else if (StatementRule.has(name))                rule = StatementRule.getJsonArray(name);
        else if (name.equals("Statement") || name.equals("Stmt")) {      // statement ใดๆ (ใช้ซ้อนใน block)
            Nodes n = Statement();
            if (n == null) return false;
            out.add(n);
            return true;
        }
        else Error("MonoClip", "Unknown grammar rule '%" + name + "'", 1);

        List<Object> sub = matchSequence(rule, depth + 1);
        if (sub == null) return false;

        if (sub.size() == 1) out.add(sub.get(0));
        else if (sub.size() > 1) out.add(sub);
        return true;
    }

    // =========================================================================
    // Expression (Pratt / precedence climbing)
    //
    //   "pratt": {
    //     "Expr": {
    //       "primary": ["choice", "collect:number", "collect:string", "collect:ident"],
    //       "group":   ["(", ")"],
    //       "levels": [ {"ops": ["or","||"]}, {"ops": ["+","-"]}, {"ops": ["**"], "assoc": "right"} ],
    //       "prefix": ["-", "!", "not"],  "prefixPrec": 6,
    //       "postfix": ["++", {"name": "Call", "seq": ["(", ["sep0","%Expr",","], ")"]}]
    //     }
    //   }
    // =========================================================================
    private static final class Pratt {
        Object primary;
        final Map<String, int[]> bin = new HashMap<>();
        final Set<String> prefix = new HashSet<>();
        final List<Object> postfix = new ArrayList<>();
        int prefixPrec;
    }

    private Pratt prattOf(String name) {
        Pratt p = prattCache.get(name);
        if (p != null) return p;

        Json.JsonObject cfg = PrattRule.getJsonObject(name);
        p = new Pratt();

        Object prim = cfg.get("primary");
        if (cfg.has("group")) {
            Json.JsonArray gr = cfg.getJsonArray("group");
            prim = grp("choice", grp("seq", gr.get(0), "%" + name, gr.get(1)), prim);
        }
        p.primary = prim;

        Json.JsonArray levels = cfg.has("levels") ? cfg.getJsonArray("levels") : new Json.JsonArray();
        for (int i = 0; i < levels.size(); i++) {
            Object lv = levels.get(i);
            boolean right = false;
            Json.JsonArray ops;
            if (lv instanceof Json.JsonObject) {
                Json.JsonObject o = (Json.JsonObject) lv;
                ops = o.getJsonArray("ops");
                right = "right".equals(o.optString("assoc", "left"));
            } else {
                ops = (Json.JsonArray) lv;
            }
            for (Object op : ops) p.bin.put(String.valueOf(op), new int[]{i, right ? 1 : 0});
        }

        if (cfg.has("prefix")) for (Object op : cfg.getJsonArray("prefix")) p.prefix.add(String.valueOf(op));
        p.prefixPrec = cfg.optInt("prefixPrec", levels.size());
        if (cfg.has("postfix")) for (Object x : cfg.getJsonArray("postfix")) p.postfix.add(x);

        prattCache.put(name, p);
        return p;
    }

    private Object parseExpr(Pratt c, int minPrec, int depth) {
        int start = this.pc;
        Object left = parseUnary(c, depth);
        if (left == null) {
            this.pc = start;
            return null;
        }
        while (!atEOF()) {
            Tok t = Current();
            if (!plain(t)) break;
            int[] lv = c.bin.get(t.value);
            if (lv == null || lv[0] < minPrec) break;

            int save = this.pc;
            consume();
            Object right = parseExpr(c, lv[1] == 1 ? lv[0] : lv[0] + 1, depth);
            if (right == null) {
                this.pc = save;
                break;
            }
            Nodes n = new Nodes();
            n.name = "Binary";
            n.Left = left;
            n.Op = t.value;
            n.Right = right;
            at(n, t);
            left = n;
        }
        return left;
    }

    private Object parseUnary(Pratt c, int depth) {
        int start = this.pc;

        if (!atEOF() && plain(Current()) && c.prefix.contains(Current().value)) {
            Tok ot = Current();
            consume();
            Object operand = parseExpr(c, c.prefixPrec, depth);
            if (operand == null) {
                this.pc = start;
                return null;
            }
            Nodes n = new Nodes();
            n.name = "Unary";
            n.Op = ot.value;
            n.Right = operand;
            at(n, ot);
            return n;
        }

        List<Object> tmp = new ArrayList<>();
        if (!matchElement(c.primary, tmp, depth)) {
            this.pc = start;
            return null;
        }
        Object v = tmp.size() == 1 ? tmp.get(0) : tmp.size() > 1 ? tmp : rawText(start, this.pc);

        boolean again = true;
        while (again) {
            again = false;
            for (Object pf : c.postfix) {
                int save = this.pc;
                Tok pt = Current();
                if (pf instanceof String) {
                    if (!atEOF() && plain(pt) && pf.equals(pt.value)) {
                        Nodes n = new Nodes();
                        n.name = "Postfix";
                        n.Left = v;
                        n.Op = pf;
                        at(n, pt);
                        consume();
                        v = n;
                        again = true;
                        break;
                    }
                } else if (pf instanceof Json.JsonObject) {
                    Json.JsonObject o = (Json.JsonObject) pf;
                    List<Object> args = new ArrayList<>();
                    if (matchItems(asList(o.getJsonArray("seq")), 0, args, depth) && this.pc != save) {
                        Nodes n = new Nodes();
                        n.name = o.optString("name", "Postfix");
                        n.Left = v;
                        n.args = args;
                        at(n, pt);
                        v = n;
                        again = true;
                        break;
                    }
                    this.pc = save;
                }
            }
        }
        return v;
    }

    // =========================================================================
    // Statement Functions
    // =========================================================================
    private Nodes Statement() {
        if (++stmtDepth > MAX_RULE_DEPTH) Error("MonoClip", "Statements nested too deep", 1);
        try {
            Tok st = Current();
            for (String key : StatementRule.keys()) {
                List<Object> out = matchSequence(StatementRule.getJsonArray(key), 0);
                if (out != null) {
                    Nodes n = new Nodes();
                    n.name = key;
                    n.args = out;
                    at(n, st);
                    return n;
                }
            }
            return null;
        } finally {
            stmtDepth--;
        }
    }

    private void Statements() {
        while (!atEOF()) {
            int before = this.pc;
            this.farPc = before;
            Nodes stmt = Statement();

            if (stmt == null) {
                if (curIs("newline")) {                // NEWLINE เกินมา (บรรทัดว่าง/; ซ้ำ) ข้ามได้
                    consume();
                    continue;
                }
                this.pc = Math.max(this.farPc, before);
                Error("SyntaxError", "Unexpected " + describe(this.Current()), 1);
            }
            if (this.pc == before) {
                Error("MonoClip", "Statement '" + stmt.name + "' consumed no tokens", 1);
            }
            Ast.add(stmt);
            // Execute(stmt); // try in test
        }
    }

    // =========================================================================
    // Execute / Eval Functions
    // =========================================================================
    private void Execute(Nodes stmt) {
        switch (stmt.name) {
            case "Print":
                System.out.println(stmt.args.isEmpty() ? "" : Eval(stmt.args.get(0)));
                break;
            default:
                Warning("MonoClip", "No executor for statement '" + stmt.name + "'");
        }
    }

    public Object Eval(Object o) {
        if (o instanceof Val) return ((Val) o).value;
        if (o instanceof Nodes) {
            Nodes n = (Nodes) o;
            if ("Binary".equals(n.name)) return evalBinary(String.valueOf(n.Op), Eval(n.Left), Eval(n.Right));
            if ("Unary".equals(n.name))  return evalUnary(String.valueOf(n.Op), Eval(n.Right));
        }
        return o;
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        if (o instanceof String) return !((String) o).isEmpty();
        return true;
    }

    private Object evalUnary(String op, Object r) {
        switch (op) {
            case "!": case "not": return !truthy(r);
            case "+": if (r instanceof Number) return r; break;
            case "-":
                if (r instanceof Long)   return -((Long) r);
                if (r instanceof Number) return -((Number) r).doubleValue();
                break;
            default: break;
        }
        Warning("MonoClip", "Cannot apply unary '" + op + "' to " + r);
        return null;
    }

    private Object evalBinary(String op, Object l, Object r) {
        switch (op) {
            case "&&": case "and": return truthy(l) && truthy(r);
            case "||": case "or":  return truthy(l) || truthy(r);
            default: break;
        }
        if (l instanceof Number && r instanceof Number) {
            Number a = (Number) l, b = (Number) r;
            boolean ints = a instanceof Long && b instanceof Long;
            long x = a.longValue(), y = b.longValue();
            double dx = a.doubleValue(), dy = b.doubleValue();
            switch (op) {
                case "+": return ints ? (Object) (x + y) : (Object) (dx + dy);
                case "-": return ints ? (Object) (x - y) : (Object) (dx - dy);
                case "*": return ints ? (Object) (x * y) : (Object) (dx * dy);
                case "/":
                    if (ints && y != 0 && x % y == 0) return x / y;
                    return dx / dy;
                case "%": return ints && y != 0 ? (Object) (x % y) : (Object) (dx % dy);
                case "**": {
                    double p = Math.pow(dx, dy);
                    if (ints && y >= 0 && Math.abs(p) < 9.0e18) return (long) p;
                    return p;
                }
                case "==": return dx == dy;
                case "!=": return dx != dy;
                case "<":  return dx < dy;
                case ">":  return dx > dy;
                case "<=": return dx <= dy;
                case ">=": return dx >= dy;
                default: break;
            }
        }
        if (op.equals("+") && (l instanceof String || r instanceof String)) return String.valueOf(l) + String.valueOf(r);
        if (op.equals("==")) return Objects.equals(l, r);
        if (op.equals("!=")) return !Objects.equals(l, r);
        Warning("MonoClip", "Cannot apply '" + op + "' to " + l + " and " + r);
        return null;
    }

    // =========================================================================
    // Loader Functions
    // =========================================================================
    private void applyRule(Json.JsonObject main) {
        this.MainRule = main;
        this.GrammarRule = main.getJsonObject("grammar");
        this.LexerRule = main.getJsonObject("lexer");
        this.StatementRule = GrammarRule.getJsonObject("statements");
        this.ExprRule = GrammarRule.has("exprs") ? GrammarRule.getJsonObject("exprs") : new Json.JsonObject();
        this.PrattRule = GrammarRule.has("pratt") ? GrammarRule.getJsonObject("pratt") : new Json.JsonObject();
        this.ClassRule = GrammarRule.has("classes") ? GrammarRule.getJsonObject("classes") : new Json.JsonObject();

        regexCache.clear();
        charSetCache.clear();
        prattCache.clear();
        shorthandCache.clear();
        loadLayout(main);
        loadTokenTypes();
        loadKeywords();
        loadAuto();
    }

    /** parse ข้อความ rule แล้ว apply — ทุกข้อผิดพลาดกลายเป็น MonoClipException("RuleError") */
    private void loadRuleText(String text, String source) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            throw new MonoClipException("RuleError", "invalid JSON in " + source + ": " + e.getMessage(), 0, 0, 1, e);
        }
        if (!(root instanceof Json.JsonObject)) {
            throw new MonoClipException("RuleError", source + " must be a JSON object", 0, 0, 1);
        }
        try {
            applyRule((Json.JsonObject) root);
        } catch (MonoClipException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MonoClipException("RuleError", "invalid rule in " + source + ": " + e, 0, 0, 1, e);
        }
    }

    private void loadTokenTypes() {
        TypeMap.clear();
        for (String[] row : DEFAULT_TYPES) {
            Set<String> s = new HashSet<>();
            for (int i = 1; i < row.length; i++) s.add(row[i]);
            TypeMap.put(row[0], s);
        }
        TypeMap.put("newline", new HashSet<>(Arrays.asList(layout.nlType)));
        TypeMap.put("indent",  new HashSet<>(Arrays.asList(layout.inType)));
        TypeMap.put("dedent",  new HashSet<>(Arrays.asList(layout.dedType)));
        if (GrammarRule.has("tokenTypes")) {
            for (Map.Entry<String, Object> e : GrammarRule.getJsonObject("tokenTypes")) {
                Set<String> s = new HashSet<>();
                if (e.getValue() instanceof Json.JsonArray) {
                    for (Object x : (Json.JsonArray) e.getValue()) s.add(String.valueOf(x).toUpperCase());
                } else if (e.getValue() != null) {
                    s.add(String.valueOf(e.getValue()).toUpperCase());
                }
                TypeMap.put(e.getKey().toLowerCase(), s);
            }
        }
    }

    private void loadKeywords() {
        KeywordAll.clear();
        KeywordGroups.clear();
        KeywordIgnoreCase = GrammarRule.optBoolean("keywordsIgnoreCase", false);

        Object kws = null;
        if (GrammarRule.has("keywords")) kws = GrammarRule.get("keywords");
        else if (LexerRule != null && LexerRule.has("keywords") && LexerRule.get("keywords") instanceof Json.JsonArray) {
            kws = LexerRule.get("keywords");
        }

        if (kws instanceof Json.JsonArray) {
            for (Object x : (Json.JsonArray) kws) addKeyword(null, String.valueOf(x));
        } else if (kws instanceof Json.JsonObject) {
            for (Map.Entry<String, Object> e : (Json.JsonObject) kws) {
                if (e.getValue() instanceof Json.JsonArray) {
                    for (Object x : (Json.JsonArray) e.getValue()) addKeyword(e.getKey(), String.valueOf(x));
                } else if (e.getValue() != null) {
                    addKeyword(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
    }

    private void addKeyword(String group, String word) {
        String w = KeywordIgnoreCase ? word.toLowerCase() : word;
        KeywordAll.add(w);
        if (group != null) KeywordGroups.computeIfAbsent(group, k -> new HashSet<>()).add(w);
    }

    private void loadAuto() {
        collectMeta = GrammarRule.optBoolean("collectMeta", false);
        Json.JsonObject ao = GrammarRule.has("auto") ? GrammarRule.getJsonObject("auto") : null;
        autoList  = pairOf(ao, "list",  "[", "]");
        autoMap   = pairOf(ao, "map",   "{", "}");
        autoTuple = pairOf(ao, "tuple", "(", ")");
        codeB     = pairOf(ao, "code",  "{", "}");
        autoSep   = strSet(ao, "sep", ",");
        autoPair  = strSet(ao, "pair", ":");
        autoTrailing = ao == null || ao.optBoolean("trailingComma", true);
        rawJoin   = ao == null ? " " : ao.optString("rawJoin", " ");

        blocks.clear();
        if (GrammarRule.has("blocks")) {
            for (Map.Entry<String, Object> e : GrammarRule.getJsonObject("blocks")) {
                if (!(e.getValue() instanceof Json.JsonObject)) configError("blocks." + e.getKey() + " must be {open, close}");
                Json.JsonObject b = (Json.JsonObject) e.getValue();
                blocks.put(e.getKey(), new String[]{b.getString("open"), b.getString("close")});
            }
        }
    }

    /**
     * โหลด rule จากไฟล์
     * @throws MonoClipException("RuleError") ถ้าไม่พบไฟล์ (และไม่ได้เปิด LoadDefRuleIfRuleFileNotFound_Enable) หรือ rule ผิด
     * @throws java.io.IOException ถ้าอ่านไฟล์ไม่ได้
     */
    public void loadRulePath(Path rulePath) throws Exception {
        if (!Files.exists(rulePath) || Files.isDirectory(rulePath)) {
            if (LoadDefRuleIfRuleFileNotFound_Enable) {
                Warning("MonoClip", "GrammarRule file not found: " + rulePath + " (using default rule)");
                loadRuleText(DefRule, "default rule");
                return;
            }
            throw new MonoClipException("RuleError", "GrammarRule file not found: " + rulePath, 0, 0, 1);
        }
        loadRuleText(Files.readString(rulePath), rulePath.toString());
    }

    public void loadRuleString(String rule) {
        loadRuleText(rule, "rule string");
    }

    public void loadRuleDef() {
        loadRuleText(DefRule, "default rule");
    }

    // =========================================================================
    // Debug Functions
    // =========================================================================
    public String dumpAstTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("AST (").append(Ast.size()).append(" statements)\n");
        for (int i = 0; i < Ast.size(); i++) {
            dumpTree("", Ast.get(i), "", i == Ast.size() - 1, sb);
        }
        return sb.toString();
    }

    public String dumpAstJson() {
        List<Object> list = new ArrayList<>();
        for (Nodes n : Ast) list.add(astToMap(n));
        return Json.stringify(list, 2);
    }

    private void dumpTree(String label, Object node, String prefix, boolean last, StringBuilder sb) {
        String head = label.isEmpty() ? "" : label + " = ";
        String childPrefix = prefix + (last ? "   " : "│  ");
        LinkedHashMap<String, Object> kids = new LinkedHashMap<>();

        sb.append(prefix).append(last ? "└─ " : "├─ ");

        if (node instanceof Nodes) {
            Nodes n = (Nodes) node;
            sb.append(head).append("Nodes<").append(n.name == null ? "?" : n.name).append(">");
            if (n.line > 0) sb.append(" @").append(n.line).append(':').append(n.col);
            if (n.level > 0) sb.append(" level=").append(n.level);
            sb.append("\n");
            if (n.Left  != null) kids.put("Left", n.Left);
            if (n.Op    != null) kids.put("Op", n.Op);
            if (n.Right != null) kids.put("Right", n.Right);
            if (n.args  != null) kids.put("args", n.args);
            if (n.nodes != null) kids.put("nodes", n.nodes);
        } else if (node instanceof Val) {
            Val v = (Val) node;
            sb.append(head).append("Val<").append(v.type).append("> @").append(v.line).append(':').append(v.col).append("\n");
            kids.put("value", v.value);
        } else if (node instanceof List) {
            List<?> l = (List<?>) node;
            sb.append(head).append("List[").append(l.size()).append("]\n");
            for (int i = 0; i < l.size(); i++) kids.put("[" + i + "]", l.get(i));
        } else if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            sb.append(head).append("Map{").append(m.size()).append("}\n");
            for (Map.Entry<?, ?> e : m.entrySet()) kids.put(String.valueOf(e.getKey()), e.getValue());
        } else {
            sb.append(head).append(formatLeaf(node)).append("\n");
            return;
        }

        int i = 0, n = kids.size();
        for (Map.Entry<String, Object> e : kids.entrySet()) {
            dumpTree(e.getKey(), e.getValue(), childPrefix, ++i == n, sb);
        }
    }

    private String formatLeaf(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            String s = ((String) v).replace("\n", "\\n").replace("\t", "\\t");
            return "\"" + s + "\"";
        }
        if (v instanceof Tok) {
            Tok t = (Tok) v;
            return "Tok<" + t.type + "> \"" + t.value.replace("\n", "\\n") + "\" @" + t.line + ":" + t.col;
        }
        if (v instanceof Json.JsonObject || v instanceof Json.JsonArray) {
            return Json.stringify(v) + " (" + v.getClass().getSimpleName() + ")";
        }
        return v + " (" + v.getClass().getSimpleName() + ")";
    }

    private Object astToMap(Object o) {
        if (o instanceof Nodes) {
            Nodes n = (Nodes) o;
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.name);
            if (n.line > 0)  { m.put("line", (long) n.line); m.put("col", (long) n.col); }
            if (n.level > 0) m.put("level", (long) n.level);
            if (n.Left  != null) m.put("Left",  astToMap(n.Left));
            if (n.Op    != null) m.put("Op",    astToMap(n.Op));
            if (n.Right != null) m.put("Right", astToMap(n.Right));
            if (n.args  != null) m.put("args",  astToMap(n.args));
            if (n.nodes != null) m.put("nodes", astToMap(n.nodes));
            return m;
        }
        if (o instanceof Val) {
            Val v = (Val) o;
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("type", v.type);
            m.put("value", astToMap(v.value));
            m.put("line", (long) v.line);
            m.put("col", (long) v.col);
            return m;
        }
        if (o instanceof Tok) {
            Tok t = (Tok) o;
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.type);
            m.put("value", t.value);
            m.put("line", (long) t.line);
            m.put("col", (long) t.col);
            return m;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<?>) o) out.add(astToMap(x));
            return out;
        }
        if (o instanceof Map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) out.put(String.valueOf(e.getKey()), astToMap(e.getValue()));
            return out;
        }
        return o;
    }

    // =========================================================================
    // Public Functions
    // =========================================================================
    public void cleaning() {
        this.pc = 0;
        this.farPc = 0;
        this.stmtDepth = 0;
        this.Ast.clear();
        this.Warnings.clear();
    }

    private static int toInt(Object o) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * lex + parse (เรียกซ้ำได้ มี cleaning() ในตัว)
     * @throws MonoClipException ถ้า syntax / indentation / rule ผิด — ผลที่ parse ได้ก่อนหน้าจะค้างอยู่ใน Ast
     */
    public int parse(String Code) {
        this.cleaning();
        this.Tokens = null;                            // ให้ตำแหน่ง error ตอน lex เป็น 0:0 ไม่อิงของรอบก่อน
        MonoLexer.Config cfg = MonoLexer.loadConfig(LexerRule);
        MonoLexer lexer = new MonoLexer(Code, cfg);

        List<Tok> toks = new ArrayList<>();
        for (MonoLexer.Token t : lexer.run()) {
            toks.add(new Tok(String.valueOf(t.type).toUpperCase(),
                             t.value == null ? "" : String.valueOf(t.value),
                             toInt(t.line), toInt(t.col)));
        }
        if (toks.isEmpty() || !typeIs(toks.get(toks.size() - 1), "eof")) {
            int ln = toks.isEmpty() ? 1 : toks.get(toks.size() - 1).line;
            toks.add(new Tok("EOF", "", ln, 0));
        }

        this.Tokens = applyLayout(toks, Code);
        this.Statements();
        if (DebugAst_Enable) {
            System.out.println(dumpAstTree());
        }
        return 0;
    }

    public MonoClip() {

    }
}