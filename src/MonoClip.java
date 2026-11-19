import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class MonoClip {
    public Json.JsonObject MainRule;
    public Json.JsonObject GrammarRule;
    public Json.JsonObject StatementRule;
    public Json.JsonObject ExprRule;
    public Json.JsonObject LexerRule;
    public boolean LoadDefRuleIfRuleFileNotFound_Enable = false;
    private String DefRule = "";
    private List<MonoLexer.Token> Tokens;
    private int pc = 0;
    public List<Nodes> Ast = new ArrayList<>();      // เก็บ statement ที่ parse ได้ทั้งหมด
    public boolean DebugAst_Enable = false;          // true = พิมพ์ AST หลัง parse เสร็จ

    public static final class Nodes {
        public String name;          // ชื่อ statement เช่น "Print"
        public List<Object> args;
        public List<Object> nodes;
        public Object Left;
        public Object Op;
        public Object Right;
    }

    // =========================================================================
    // Opers Functions
    // =========================================================================
    private MonoLexer.Token Current() {
        if (this.pc >= this.Tokens.size()) {
            return this.Tokens.get(this.Tokens.size()-1);
        }
        return this.Tokens.get(this.pc);
    }

    private int Advance() {
        if (this.pc+1 >= this.Tokens.size()) {
            return 1;
        }
        pc += 1;
        return 0;
    }

    private int Advance(int k) {
        if (this.pc+k >= this.Tokens.size()) {
            return 1;
        }
        pc += k;
        return 0;
    }

    private void Warning(String name,String content) {
        System.err.println("[Warning] (%s) %s InLine %s:%s".formatted(name,content,this.Current().line,this.Current().col));
        return;
    }

    private void Error(String name,String content,int code) {
        System.err.println("[%s] %s InLine %s:%s".formatted(name,content,this.Current().line,this.Current().col));
        System.exit(code);
    }

        // =========================================================================
    // Token Helper Functions
    //   ฟังก์ชันช่วยตรวจ token ปัจจุบัน (ไม่ขยับ pc ยกเว้นระบุไว้)
    // =========================================================================

    /** ตรวจว่า token เป็น "ชนิด" ที่ grammar เรียก เช่น collect:string / collect:number
     *  ปรับชื่อ type ให้ตรงกับที่ MonoLexer ของคุณสร้างจริง */
    private boolean isTokenKind(MonoLexer.Token tok, String kind) {
        String t = String.valueOf(tok.type);
        switch (kind.toLowerCase()) {
            case "any":    return !t.equals("EOF");
            case "string": return t.equals("STRING") || t.equals("RAWSTRING");
            case "number": return t.equals("NUMBER") || t.equals("INT") || t.equals("FLOAT");
            case "ident":  return t.equals("IDENT") || t.equals("IDENTIFIER") || t.equals("ID");
            default:       return t.equalsIgnoreCase(kind);   // เทียบ type ตรงๆ
        }
    }

    private boolean atEOF() {
        return String.valueOf(this.Current().type).equals("EOF");
    }

    // =========================================================================
    // Grammar Matching Functions
    //   หัวใจของการอ่าน Grammar: match ทีละ element ใน array
    //   ถ้า match ไม่ครบ จะ "ย้อน pc กลับ" (backtrack) แล้วคืน null
    // =========================================================================
    private static final int MAX_RULE_DEPTH = 64;   // กัน rule อ้างอิงวนไม่รู้จบ

    /**
     * Match ทั้ง sequence เช่น ["print","%String"]
     * @return List ของค่าที่เก็บได้ (ว่างได้) หรือ null ถ้า match ไม่สำเร็จ
     */
    private List<Object> matchSequence(Json.JsonArray seq, int depth) {
        if (depth > MAX_RULE_DEPTH) {
            Error("MonoClip", "Grammar recursion too deep", 1);
        }
        int start = this.pc;                       // จุดย้อนกลับ
        List<Object> out = new ArrayList<>();

        for (Object o : seq) {
            if (!matchElement(String.valueOf(o), out, depth)) {
                this.pc = start;                   // backtrack
                return null;
            }
        }
        return out;
    }

    /**
     * Match element เดียว แล้วเพิ่มผลลง out (ถ้ามีค่าให้เก็บ)
     * @return true ถ้าสำเร็จ (pc ขยับไปแล้ว), false ถ้าไม่ตรง (pc ไม่ขยับ)
     */
    private boolean matchElement(String el, List<Object> out, int depth) {

        // ---- %Name : อ้างอิง rule อื่น ----
        if (el.startsWith("%")) {
            return matchRuleRef(el.substring(1), out, depth);
        }

        // ---- collect:kind : เก็บ token ตามชนิด ----
        if (el.startsWith("collect:")) {
            return matchCollect(el.substring("collect:".length()), out);
        }

        // ---- type:NAME : ตรวจ type ตรงตัว (ไม่เก็บค่า) ----
        if (el.startsWith("type:")) {
            if (!isTokenKind(this.Current(), el.substring("type:".length()))) return false;
            this.Advance();
            return true;
        }

        // ---- literal : เทียบ value ตรงๆ (ไม่เก็บค่า) ----
        return matchLiteral(el);
    }

    /** literal เช่น "print" "(" ";" */
    private boolean matchLiteral(String text) {
        if (atEOF() || !text.equals(this.Current().value)) return false;
        this.Advance();
        return true;
    }

    /** collect:string -> เก็บ value ของ token ลง out */
    private boolean matchCollect(String kind, List<Object> out) {
        MonoLexer.Token tok = this.Current();
        if (!isTokenKind(tok, kind)) return false;
        out.add(tok.value);
        this.Advance();
        return true;
    }

    /** %Name -> หา rule ใน exprs ก่อน แล้วค่อย statements */
    private boolean matchRuleRef(String name, List<Object> out, int depth) {
        Json.JsonArray rule = null;
        if (ExprRule.has(name))           rule = ExprRule.getJsonArray(name);
        else if (StatementRule.has(name)) rule = StatementRule.getJsonArray(name);
        else Error("MonoClip", "Unknown grammar rule '%" + name + "'", 1);

        List<Object> sub = matchSequence(rule, depth + 1);
        if (sub == null) return false;

        // ถ้า rule ย่อยให้ค่าเดียว ให้แบนเป็นค่านั้นเลย ไม่งั้นเก็บเป็น List
        if (sub.size() == 1) out.add(sub.get(0));
        else if (sub.size() > 1) out.add(sub);
        return true;
    }

    // =========================================================================
    // Statement Functions
    //   ลองทุก statement ใน grammar ถ้าอันไหนตรงก็คืน Nodes
    // =========================================================================

    /** ลอง match 1 statement ที่ตำแหน่ง pc ปัจจุบัน (คืน null ถ้าไม่มีอันไหนตรง) */
    private Nodes Statement() {
        for (String key : StatementRule.keys()) {
            List<Object> out = matchSequence(StatementRule.getJsonArray(key), 0);
            if (out != null) {
                Nodes n = new Nodes();
                n.name = key;
                n.args = out;
                return n;
            }
        }
        return null;
    }

    /** อ่าน statement ต่อเนื่องจนจบไฟล์ */
    private void Statements() {
        while (!atEOF()) {
            int before = this.pc;
            Nodes stmt = Statement();

            if (stmt == null) {
                Error("SyntaxError", "Unexpected token '" + this.Current().value + "'", 1);
            }
            if (this.pc == before) {   // กัน infinite loop ถ้า rule ว่างเปล่า
                Error("MonoClip", "Statement '" + stmt.name + "' consumed no tokens", 1);
            }
            Ast.add(stmt);
            // Execute(stmt); // try in test
        }
    }

    // =========================================================================
    // Execute Functions
    //   จุดต่อยอด: ทำงานตามชื่อ statement (ภายหลังจะเปลี่ยนเป็นสร้าง AST ก็ได้)
    // =========================================================================
    private void Execute(Nodes stmt) {
        switch (stmt.name) {
            case "Print":
                System.out.println(stmt.args.get(0));
                break;
            default:
                Warning("MonoClip", "No executor for statement '" + stmt.name + "'");
        }
    }

    // =========================================================================
    // Loader Functions
    // =========================================================================
    public void loadRulePath(Path rulePath) throws Exception {
        if (!Files.exists(rulePath) || Files.isDirectory(rulePath)) {
            System.err.println("[Warning] (MonoClip) GrammarRule file not found");
            if (LoadDefRuleIfRuleFileNotFound_Enable) {
                this.MainRule = (Json.JsonObject) Json.parse(DefRule);
                this.GrammarRule = (Json.JsonObject) MainRule.getJsonObject("grammar");
                this.LexerRule = (Json.JsonObject) MainRule.getJsonObject("lexer");
                this.StatementRule = GrammarRule.getJsonObject("statements");
                this.ExprRule = GrammarRule.getJsonObject("exprs");
                return;
            }
            System.exit(0);
        }
        this.MainRule = (Json.JsonObject) Json.parse(Files.readString(rulePath));
        this.GrammarRule = (Json.JsonObject) MainRule.getJsonObject("grammar");
        this.LexerRule = (Json.JsonObject) MainRule.getJsonObject("lexer");
        this.StatementRule = GrammarRule.getJsonObject("statements");
        this.ExprRule = GrammarRule.getJsonObject("exprs");
    }

    public void loadRuleString(String rule) {
        this.MainRule = (Json.JsonObject) Json.parse(rule);
        this.GrammarRule = (Json.JsonObject) MainRule.getJsonObject("grammar");
        this.LexerRule = (Json.JsonObject) MainRule.getJsonObject("lexer");
        this.StatementRule = GrammarRule.getJsonObject("statements");
        this.ExprRule = GrammarRule.getJsonObject("exprs");
    }

    public void loadRuleDef() {
        this.MainRule = (Json.JsonObject) Json.parse(DefRule);
        this.GrammarRule = (Json.JsonObject) MainRule.getJsonObject("grammar");
        this.LexerRule = (Json.JsonObject) MainRule.getJsonObject("lexer");
        this.StatementRule = GrammarRule.getJsonObject("statements");
        this.ExprRule = GrammarRule.getJsonObject("exprs");
    }

    // =========================================================================
    // Debug Functions
    //   dumpAstTree() -> แสดงเป็นต้นไม้ อ่านง่าย
    //   dumpAstJson() -> แสดงเป็น JSON pretty-print (เอาไปเทียบ/เก็บไฟล์ได้)
    // =========================================================================

    /** แสดง AST เป็น tree เช่น
     *  AST (1 statements)
     *  └─ Nodes<Print>
     *     └─ args = List[1]
     *        └─ [0] = "hello"
     */
    public String dumpAstTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("AST (").append(Ast.size()).append(" statements)\n");
        for (int i = 0; i < Ast.size(); i++) {
            dumpTree("", Ast.get(i), "", i == Ast.size() - 1, sb);
        }
        return sb.toString();
    }

    /** แสดง AST เป็น JSON */
    public String dumpAstJson() {
        List<Object> list = new ArrayList<>();
        for (Nodes n : Ast) list.add(astToMap(n));
        return Json.stringify(list, 2);
    }

    // ---- ตัววาด tree (recursive) ----
    private void dumpTree(String label, Object node, String prefix, boolean last, StringBuilder sb) {
        String head = label.isEmpty() ? "" : label + " = ";
        String childPrefix = prefix + (last ? "   " : "│  ");
        java.util.LinkedHashMap<String, Object> kids = new java.util.LinkedHashMap<>();

        sb.append(prefix).append(last ? "└─ " : "├─ ");

        if (node instanceof Nodes) {
            Nodes n = (Nodes) node;
            sb.append(head).append("Nodes<").append(n.name == null ? "?" : n.name).append(">\n");
            if (n.Left  != null) kids.put("Left", n.Left);
            if (n.Op    != null) kids.put("Op", n.Op);
            if (n.Right != null) kids.put("Right", n.Right);
            if (n.args  != null) kids.put("args", n.args);
            if (n.nodes != null) kids.put("nodes", n.nodes);
        } else if (node instanceof List) {
            List<?> l = (List<?>) node;
            sb.append(head).append("List[").append(l.size()).append("]\n");
            for (int i = 0; i < l.size(); i++) kids.put("[" + i + "]", l.get(i));
        } else {
            sb.append(head).append(formatLeaf(node)).append("\n");
            return;
        }

        int i = 0, n = kids.size();
        for (java.util.Map.Entry<String, Object> e : kids.entrySet()) {
            dumpTree(e.getKey(), e.getValue(), childPrefix, ++i == n, sb);
        }
    }

    /** ค่าปลายทาง: String ใส่ "" ให้เห็นขอบเขต, null แสดงเป็น null */
    private String formatLeaf(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            String s = ((String) v).replace("\n", "\\n").replace("\t", "\\t");
            return "\"" + s + "\"";
        }
        return v + " (" + v.getClass().getSimpleName() + ")";
    }

    // ---- แปลง Nodes -> Map/List เพื่อส่งให้ Json.stringify ----
    private Object astToMap(Object o) {
        if (o instanceof Nodes) {
            Nodes n = (Nodes) o;
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", n.name);
            if (n.Left  != null) m.put("Left",  astToMap(n.Left));
            if (n.Op    != null) m.put("Op",    astToMap(n.Op));
            if (n.Right != null) m.put("Right", astToMap(n.Right));
            if (n.args  != null) m.put("args",  astToMap(n.args));
            if (n.nodes != null) m.put("nodes", astToMap(n.nodes));
            return m;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<?>) o) out.add(astToMap(x));
            return out;
        }
        return o;   // String / Number / Boolean / null
    }

    // =========================================================================
    // Public Functions
    // =========================================================================
    public void cleaning() {
        this.pc = 0;
        this.Ast.clear();
    }

    public int parse(String Code) {
        this.cleaning();
        MonoLexer.Config cfg = MonoLexer.loadConfig(LexerRule);
        MonoLexer lexer = new MonoLexer(Code, cfg);
        this.Tokens = lexer.run();
        this.Statements();
        if (DebugAst_Enable) {
            System.out.println(dumpAstTree());
        }
        return 0;
    }
    
    MonoClip() {

    }
}