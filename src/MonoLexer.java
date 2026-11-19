import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MonoLexer.java — Lexer ของ Mono (แยกจาก Mono_py10.py)
 *
 * อ่าน lexer.json ด้วย Json.java เท่านั้น ไม่เกี่ยวกับ grammar/normalizer
 *
 *   MonoLexer.Config cfg = MonoLexer.loadConfig(Paths.get("lexer.json"));
 *   List<MonoLexer.Token> tokens = new MonoLexer(code, cfg).run();
 *
 * CLI: java MonoLexer lexer.json source.mono
 * (ต้อง compile คู่กับ Json.java)
 */
public final class MonoLexer {

    // ─────────────────────────────────────────────
    //  ERROR HANDLING
    // ─────────────────────────────────────────────

    /** error ที่ policy เป็น "exit" */
    public static class LexerException extends RuntimeException {
        public LexerException(String message) { super(message); }
    }

    private static final Map<String, String> DEFAULT_ERROR_POLICY = new LinkedHashMap<>();
    static {
        DEFAULT_ERROR_POLICY.put("unknown_token",    "exit");
        DEFAULT_ERROR_POLICY.put("unclosed_string",  "exit");
        DEFAULT_ERROR_POLICY.put("invalid_char",     "exit");
        DEFAULT_ERROR_POLICY.put("unknown_operator", "warn");
        DEFAULT_ERROR_POLICY.put("rule_not_matched", "warn");
    }

    /** exit -> throw, อย่างอื่น (warn) -> พิมพ์ warning แล้วไปต่อ */
    static void handleError(String errorType, String message, Map<String, String> policy) {
        if (policy == null) policy = DEFAULT_ERROR_POLICY;
        String action = policy.getOrDefault(errorType, "exit");
        if (action.equals("exit")) {
            throw new LexerException(message);
        }
        System.err.println("[Warning] " + message);
    }

    // ─────────────────────────────────────────────
    //  TOKEN
    // ─────────────────────────────────────────────

    public static final class Token {
        public final String type;
        public final Object value;   // Long/BigInteger, Double, String หรือ null (EOF)
        public final int pos, line, col, tab;

        public Token(String type, Object value, int pos, int line, int col, int tab) {
            this.type = type; this.value = value;
            this.pos = pos; this.line = line; this.col = col; this.tab = tab;
        }

        @Override
        public String toString() {
            String v = (value instanceof String)
                    ? "'" + ((String) value).replace("\\", "\\\\").replace("'", "\\'")
                                            .replace("\n", "\\n").replace("\t", "\\t") + "'"
                    : String.valueOf(value);
            return type + ":" + v + "(L" + line + ",C" + col + ",tab=" + tab + ")";
        }
    }

    // ─────────────────────────────────────────────
    //  CONFIG  (โหลดจาก rule.json ด้วย Json.java)
    // ─────────────────────────────────────────────

    /** custom string literal เช่น ##...## */
    public static final class StringMode {
        public final String open, close, token;
        public final Map<String, String> escape;   // null = ไม่มี escape

        StringMode(Json.JsonObject c) {
            this.open  = c.optString("open", "");
            this.close = c.optString("close", this.open);
            this.token = c.optString("token", "STRING");
            Object esc = c.opt("escape", null);
            this.escape = (esc instanceof Json.JsonObject) ? strMap((Json.JsonObject) esc) : null;
        }
    }

    public static final class Config {
        public final Map<String, String> keywords, operators, symbols, errorPolicy;
        public final List<StringMode> stringModes = new ArrayList<>();
        public final int tabSize;

        Config(Json.JsonObject j) {
            Map<String, String> pol = new LinkedHashMap<>(DEFAULT_ERROR_POLICY);
            Object p = j.opt("error_policy", null);
            if (p instanceof Json.JsonObject) pol.putAll(strMap((Json.JsonObject) p));
            this.errorPolicy = pol;

            for (String k : new String[]{"keyword", "operator", "symbol"}) {
                if (!(j.opt(k, null) instanceof Json.JsonObject)) {
                    handleError("schema_violation", "lexer.json missing object: '" + k + "'", pol);
                }
            }
            this.keywords  = strMap(j.getJsonObject("keyword"));
            this.operators = strMap(j.getJsonObject("operator"));
            this.symbols   = strMap(j.getJsonObject("symbol"));

            Object sm = j.opt("string_modes", null);
            if (sm instanceof Json.JsonObject) {
                for (Map.Entry<String, Object> e : (Json.JsonObject) sm) {
                    if (e.getValue() instanceof Json.JsonObject) {
                        StringMode m = new StringMode((Json.JsonObject) e.getValue());
                        if (!m.open.isEmpty()) stringModes.add(m);
                    }
                }
            }
            this.tabSize = j.optInt("tab_size", 4);
        }
    }

    private static Map<String, String> strMap(Json.JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : o) m.put(e.getKey(), String.valueOf(e.getValue()));
        return m;
    }

    public static Config loadConfig(Path path) throws IOException {
        return loadConfigFromString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    public static Config loadConfigFromString(String jsonText) {
        Object parsed = Json.parse(jsonText);
        if (!(parsed instanceof Json.JsonObject)) throw new LexerException("lexer.json root must be an object");
        return new Config((Json.JsonObject) parsed);
    }

    // ─────────────────────────────────────────────
    //  LEXER
    // ─────────────────────────────────────────────

    private static final int NONE = -1;   // แทน current_char = None
    private static final Map<String, String> STRING_ESCAPES = new HashMap<>();
    static {
        STRING_ESCAPES.put("n", "\n");
        STRING_ESCAPES.put("t", "\t");
        STRING_ESCAPES.put("\"", "\"");
        STRING_ESCAPES.put("\\", "\\");
    }

    private final Config cfg;
    private final int[] code;            // เก็บเป็น code point เพื่อให้ col ตรงกับ Python
    private final int tabSize;

    private int pos = 0, line = 0, col = 0;
    private int cur;
    private final StringBuilder buffer = new StringBuilder();
    private final List<Token> tokens = new ArrayList<>();
    private int lineTab = 0;

    private final Map<String, StringMode> customStrings = new HashMap<>();
    private final List<String> customOpeners;
    private StringMode currentCustom;

    private Runnable state;

    public MonoLexer(String source, Config cfg) { this(source, cfg, cfg.tabSize); }

    public MonoLexer(String source, Config cfg, int tabSize) {
        this.cfg = cfg;
        this.code = source.codePoints().toArray();
        this.tabSize = tabSize <= 0 ? 4 : tabSize;
        this.cur = code.length > 0 ? code[0] : NONE;

        for (StringMode m : cfg.stringModes) customStrings.put(m.open, m);
        // เรียงจากยาวสุดไปสั้นสุด (match multichar ก่อน)
        List<String> openers = new ArrayList<>(customStrings.keySet());
        openers.sort((a, b) -> Integer.compare(b.codePointCount(0, b.length()), a.codePointCount(0, a.length())));
        this.customOpeners = openers;

        this.state = this::stateStart;
        calcLineTab();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String str(int cp) { return new String(Character.toChars(cp)); }

    private void err(String type, String msg) { handleError(type, msg, cfg.errorPolicy); }

    private void calcLineTab() {
        int p = pos, spaces = 0;
        while (p < code.length && (code[p] == ' ' || code[p] == '\t')) {
            spaces += code[p] == '\t' ? tabSize : 1;
            p++;
        }
        lineTab = spaces / tabSize;
    }

    private void advance() {
        if (cur == '\n') { line++; col = 0; } else { col++; }
        pos++;
        if (pos < code.length) {
            cur = code[pos];
            if (col == 0) calcLineTab();
        } else {
            cur = NONE;
        }
    }

    private int peek(int offset) {
        int n = pos + offset;
        return n < code.length ? code[n] : NONE;
    }

    /** ตรวจว่าตำแหน่งปัจจุบันขึ้นต้นด้วย s หรือไม่ */
    private boolean matches(String s) {
        int i = pos;
        for (int k = 0; k < s.length(); ) {
            int c = s.codePointAt(k);
            if (i >= code.length || code[i] != c) return false;
            i++;
            k += Character.charCount(c);
        }
        return true;
    }

    private void emit(String type, Object value) {
        tokens.add(new Token(type, value, pos, line, col, lineTab));
    }

    public List<Token> run() {
        while (state != null) state.run();
        emit("EOF", null);
        return tokens;
    }

    // ── states ────────────────────────────────────────────────────────────

    private void stateStart() {
        if (cur == NONE) { state = null; return; }
        if (cur == '\n') { emit("NEWLINE", "\\n"); advance(); return; }
        if (cur == ' ' || cur == '\t' || cur == '\r') { advance(); return; }

        buffer.setLength(0);

        // custom string opener (multichar) ลองก่อน
        for (String opener : customOpeners) {
            if (matches(opener)) {
                currentCustom = customStrings.get(opener);
                int n = opener.codePointCount(0, opener.length());
                for (int i = 0; i < n; i++) advance();
                state = this::stateCustomStringBody;
                return;
            }
        }

        if (Character.isLetter(cur) || cur == '_')        state = this::stateIdentifier;
        else if (Character.isDigit(cur))                  state = this::stateNumber;
        else if (cur == '"')  { advance(); state = this::stateString; }
        else if (cur == '\'') { advance(); state = this::stateChar; }
        else if (cur == '/')                              state = this::stateSlash;
        else if ("+-*=!<>".indexOf(cur) >= 0)             state = this::stateOperator;
        else if (cfg.symbols.containsKey(str(cur)))       state = this::stateSymbol;
        else {
            err("unknown_token", "Unknown char at line " + line + ", col " + col + ": '" + str(cur) + "'");
            advance();   // กรณี policy = warn ต้องข้ามตัวอักษรนี้ ไม่งั้นวนไม่รู้จบ
        }
    }

    private void stateCustomStringBody() {
        StringMode m = currentCustom;
        String close = m.close.isEmpty() ? m.open : m.close;
        int closeLen = close.codePointCount(0, close.length());

        while (true) {
            if (cur == NONE) {
                err("unclosed_string", "Unclosed custom string (opener='" + m.open + "') at line " + line);
                state = this::stateStart;   // warn: จบที่ EOF
                return;
            }
            if (matches(close)) {
                for (int i = 0; i < closeLen; i++) advance();
                emit(m.token, buffer.toString());
                state = this::stateStart;
                return;
            }
            if (m.escape != null && cur == '\\') {
                advance();
                if (cur == NONE) continue;   // ไปชน unclosed ด้านบน
                String v = m.escape.get(str(cur));
                buffer.append(v != null ? v : str(cur));
                advance();
                continue;
            }
            buffer.appendCodePoint(cur);
            advance();
        }
    }

    private void stateIdentifier() {
        if (cur != NONE && (Character.isLetterOrDigit(cur) || cur == '_')) {
            buffer.appendCodePoint(cur);
            advance();
        } else {
            String word = buffer.toString();
            emit(cfg.keywords.getOrDefault(word, "IDENTIFIER"), word);
            state = this::stateStart;
        }
    }

    private void stateNumber() {
        boolean hasDot = false, hasExp = false;
        while (cur != NONE) {
            int c = cur;
            if (Character.isDigit(c)) {
                buffer.append((char) ('0' + Character.digit(c, 10)));   // รองรับเลขยูนิโค้ด เช่น ๑๒๓
            } else if (c == '.' && !hasDot) {
                hasDot = true; buffer.append('.');
            } else if ((c == 'e' || c == 'E') && !hasExp) {
                hasExp = true; buffer.append((char) c);
                int p = peek(1);
                if (p == '+' || p == '-') { advance(); buffer.append((char) cur); }
            } else {
                break;
            }
            advance();
        }
        String text = buffer.toString();
        try {
            if (hasDot || hasExp) {
                emit("FLOAT", Double.parseDouble(text));
            } else {
                try { emit("INT", Long.parseLong(text)); }
                catch (NumberFormatException big) { emit("INT", new BigInteger(text)); }
            }
        } catch (NumberFormatException e) {
            err("unknown_token", "Invalid number literal '" + text + "' at line " + line + ", col " + col);
        }
        state = this::stateStart;
    }

    private void stateString() {
        if (cur == NONE) {
            err("unclosed_string", "Unclosed string at line " + line + ", col " + col);
            state = this::stateStart;
            return;
        }
        if (cur == '"') {
            emit("STRING", buffer.toString());
            advance();
            state = this::stateStart;
            return;
        }
        if (cur == '\\') {
            advance();
            if (cur == NONE) return;   // รอบถัดไปจะรายงาน unclosed string
            String v = STRING_ESCAPES.get(str(cur));
            buffer.append(v != null ? v : str(cur));
        } else {
            buffer.appendCodePoint(cur);
        }
        advance();
    }

    private void stateChar() {
        if (cur == NONE) {
            err("unclosed_string", "Unclosed char literal at line " + line + ", col " + col);
            state = this::stateStart;
            return;
        }
        String value = str(cur);
        advance();
        if (cur != '\'') {
            err("invalid_char", "Invalid char literal at line " + line + ", col " + col
                    + " — expected closing \"'\"");
            state = this::stateStart;
            return;
        }
        advance();
        emit("CHAR", value);
        state = this::stateStart;
    }

    private void stateSlash() {
        int nx = peek(1);
        if (nx == '/')      { advance(); advance(); state = this::stateCommentLine; }
        else if (nx == '*') { advance(); advance(); state = this::stateCommentBlock; }
        else                { emit("DIV", "/"); advance(); state = this::stateStart; }
    }

    private void stateCommentLine() {
        while (cur != NONE && cur != '\n') advance();
        state = this::stateStart;
    }

    private void stateCommentBlock() {
        while (cur != NONE) {
            if (cur == '*' && peek(1) == '/') { advance(); advance(); break; }
            advance();
        }
        state = this::stateStart;
    }

    private void stateOperator() {
        StringBuilder op = new StringBuilder(str(cur));
        advance();
        if (cur == '=') { op.append('='); advance(); }
        String tok = cfg.operators.get(op.toString());
        if (tok == null) {
            err("unknown_operator", "Unknown operator '" + op + "' at line " + line + ", col " + col);
            state = this::stateStart;
            return;
        }
        emit(tok, op.toString());
        state = this::stateStart;
    }

    private void stateSymbol() {
        String sym = str(cur);
        advance();
        emit(cfg.symbols.get(sym), sym);
        state = this::stateStart;
    }

    /** รับ Json.JsonObject ตรงๆ (สร้างเอง หรือ parse มาแล้ว) */
    public static Config loadConfig(Json.JsonObject obj) {
        if (obj == null) throw new LexerException("lexer config must not be null");
        return new Config(obj);
    }

    // ─────────────────────────────────────────────
    //  CLI
    // ─────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: java MonoLexer <lexer.json> <source.mono>");
            System.exit(2);
        }
        Config cfg = loadConfig(Paths.get(args[0]));
        String src = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
        try {
            for (Token t : new MonoLexer(src, cfg).run()) System.out.println(t);
        } catch (LexerException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}