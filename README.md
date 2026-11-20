# MonoClip

ตัว parser ที่ **กำหนดไวยากรณ์ด้วย JSON** (ไม่ต้องแก้โค้ด Java เพื่อเปลี่ยนภาษา)
รับ token จาก `MonoLexer` → (ถ้าตั้งค่า) แทรก `NEWLINE / INDENT / DEDENT` → จับคู่ตาม grammar ใน JSON → ได้ AST เป็น `Nodes`

- รองรับภาษาแบบ Python (เยื้อง) หรือแบบ `{ }` / `;`
- นิพจน์แบบ Pratt (มี precedence, prefix, postfix, right-assoc)
- เก็บค่าอัตโนมัติ: list, map, json, code block, raw block, indent block
- มีตัว dump AST เป็นต้นไม้/JSON สำหรับ debug
- มี `Eval` เล็กๆ สำหรับคำนวณนิพจน์ (`Binary` / `Unary`)

## ไฟล์ที่ต้องมี

| ไฟล์ | หน้าที่ |
|---|---|
| `MonoClip.java` | ตัว parser |
| `MonoLexer.java` | lexer (ใช้ `MonoLexer.loadConfig(...)` อ่านส่วน `lexer` ของ rule) |
| `Json.java` | ไลบรารี JSON ไฟล์เดียว (`Json.JsonObject`, `Json.JsonArray`, `Json.parse`, `Json.stringify`) |
| `rule.json` | ไวยากรณ์ของภาษาที่จะ parse |

## เริ่มใช้งาน

```java
MonoClip p = new MonoClip();
p.DebugAst_Enable = true;                 // พิมพ์ AST เป็นต้นไม้หลัง parse
p.loadRulePath(Path.of("rule.json"));     // หรือ p.loadRuleString(jsonText)

p.parse("say 1 + 2 * 3\n");

System.out.println(p.dumpAstTree());      // ต้นไม้
System.out.println(p.dumpAstJson());      // JSON
for (MonoClip.Nodes n : p.Ast) {          // เดินเอง
    System.out.println(n.name + " " + n.args);
}
```

| เมธอด / ฟิลด์ | ความหมาย |
|---|---|
| `loadRulePath(Path)` | โหลด rule จากไฟล์ (ไม่เจอไฟล์ → เตือนแล้วจบโปรแกรม ยกเว้นเปิด `LoadDefRuleIfRuleFileNotFound_Enable`) |
| `loadRuleString(String)` | โหลด rule จากสตริง JSON |
| `loadRuleDef()` | โหลด `DefRule` ที่ฝังในคลาส |
| `parse(String code)` | lex + parse ผลลัพธ์อยู่ที่ `Ast` (เรียกซ้ำได้ มี `cleaning()` ในตัว) |
| `Ast` | `List<Nodes>` ของ statement ระดับบนสุด |
| `Eval(Object)` | คำนวณ `Val` / `Binary` / `Unary` |
| `dumpAstTree()` / `dumpAstJson()` | debug |
| `DebugAst_Enable` | พิมพ์ต้นไม้อัตโนมัติท้าย `parse` |

> ถ้า syntax ผิด จะพิมพ์ `[SyntaxError] Unexpected ... InLine บรรทัด:คอลัมน์` แล้ว `System.exit(1)`

## โครงสร้าง rule.json

```json
{
  "lexer":   { },
  "layout":  { },
  "grammar": {
    "keywords":   [],
    "tokenTypes": {},
    "auto":       {},
    "blocks":     {},
    "classes":    {},
    "exprs":      {},
    "pratt":      {},
    "statements": {}
  }
}
```

- `lexer` — ส่งต่อให้ `MonoLexer` ตรงๆ (รูปแบบดูที่ MonoLexer)
- `grammar.statements` **จำเป็น** นอกนั้นใส่หรือไม่ใส่ก็ได้
- `layout` วางที่ root หรือข้างใน `grammar` ก็ได้

### ตัวอย่างเล็กที่สุด

```json
{
  "lexer": { },
  "grammar": {
    "keywords": ["say"],
    "pratt": {
      "Expr": {
        "primary": ["choice", "collect:number", "collect:string", "collect:ident"],
        "group": ["(", ")"],
        "levels": [ {"ops": ["+", "-"]}, {"ops": ["*", "/", "%"]}, {"ops": ["**"], "assoc": "right"} ],
        "prefix": ["-", "!"]
      }
    },
    "statements": {
      "Say": ["say", "%Expr", "@nl"]
    }
  },
  "layout": { "mode": "newline" }
}
```

`say 1 + 2 * 3` → `Nodes<Say>` ที่ `args[0]` เป็น `Nodes<Binary>` (`1 + (2*3)`)

---

## 1) statements

แต่ละ key = ชื่อ statement, ค่า = **sequence** (array ของ element) parser ลองทีละ key **ตามลำดับที่เขียน** อันแรกที่ match ได้ชนะ
ผลลัพธ์: `Nodes{ name = key, args = [ค่าที่ collect ได้] }`

> เรียงกฎที่ยาว/เฉพาะเจาะจงไว้ก่อนกฎที่สั้น (เหมือน PEG)

## 2) Element ใน sequence

Element เป็น **String** หรือ **Array (group)**

### String

| รูปแบบ | ความหมาย |
|---|---|
| `"print"` `"("` | literal (ไม่เก็บค่า, ไม่ match token สตริง) |
| `lit:x` / `ilit:x` | literal แบบบังคับ / ไม่สนตัวพิมพ์ |
| `%Rule` | เรียก rule (pratt → exprs → statements) |
| `%Statement` (หรือ `%Stmt`) | statement ใดๆ (ใช้ซ้อนใน block) |
| `collect:SPEC` | **เก็บค่า** token ที่ตรง SPEC |
| `val:SPEC` | เหมือน collect แต่ห่อเป็น `Val{value,type,line,col}` |
| `type:SPEC` | ตรวจอย่างเดียว ไม่เก็บ |
| `kw:Group` | keyword จาก `grammar.keywords` |
| `[a-zA-Z_]` | token ทั้งก้อนต้องอยู่ใน char class (รองรับ `^`, ช่วง, `\d \w \s`) |
| `re:REGEX` | regex ตรงทั้งก้อน |
| `sym:+-*/=` | token ที่ประกอบจากอักขระเหล่านี้เท่านั้น |
| `class:Name` / `$Name` | custom class จาก `grammar.classes` |
| `@eof` `@any` | จบไฟล์ / token อะไรก็ได้ |
| `@nl` `@indent` `@dedent` | token สังเคราะห์จาก layout |
| `@level>=N` | ตรวจระดับ block ปัจจุบัน (ไม่กิน token) ใช้ `== != >= <= > <` |

### SPEC ที่ใช้กับ `collect:` / `type:` / `val:`

`any token string number int float ident keyword symbol bool null newline indent dedent eof`
หรือ `type:NAME` (เทียบ type ตรงตัว), ชื่อที่ประกาศใน `tokenTypes`, หรือ `re:` / `sym:` / `kw:` / `class:` ตามตารางบน

`collect:` จะแปลงค่าให้: `int`/`number` → `Long`/`Double`, `float` → `Double`, `bool` → `Boolean`, `any` → เลขถ้าเป็นเลข ไม่งั้นสตริง, `token` → ตัว `Tok` ทั้งก้อน

### Shorthand แบบ peglib

ต่อท้ายหรือนำหน้า element ได้เลย:

| | ความหมาย |
|---|---|
| `X*` `X+` `X?` | many / some / opt |
| `&X` | peek (ดูล่วงหน้า ไม่กิน) |
| `!X` | not |
| `~X` | ignore (match แต่ทิ้งผล) |

ใช้กับ `%Rule`, `$Name`, `collect:`, `val:`, `type:`, `kw:`, `class:`, `[..]`, `@nl @indent @dedent @any @eof`

### Group (Array) — ตัวแรกคือชื่อ group

| group | ความหมาย |
|---|---|
| `["choice", a, b, ...]` / `oneof` | เลือกอันแรกที่ match |
| `["seq", ...]` | ลำดับ |
| `["opt", ...]` / `optional` | มีหรือไม่มีก็ได้ |
| `["many", ...]` / `star` | 0 ครั้งขึ้นไป |
| `["some", ...]` / `plus` | 1 ครั้งขึ้นไป |
| `["rep", min, max, ...]` | จำนวนครั้งกำหนดเอง (`max = -1` ไม่จำกัด) |
| `["sep", item, sep]` | item คั่นด้วย sep (≥1) |
| `["sep0", item, sep]` | เหมือน sep แต่ได้ 0 ตัว |
| `["sept", item, sep]` | เหมือน sep0 + ยอม trailing separator |
| `["peek", ...]` / `["not", ...]` | lookahead |
| `["ignore", ...]` | match แล้วทิ้งผล |
| `["cap", ...]` | เก็บ**ข้อความดิบ**ของ token ที่ match |
| `["until", ...]` | เก็บ token จนกว่าจะเจอ pattern |
| `["any"]` / `["eof"]` | token ใดๆ / จบไฟล์ |

ผลลัพธ์ของ `many/some/sep/...` จะเป็น `List` เดียวก้อนใน `args`

### ตัวอย่าง statements

```json
"statements": {
  "Fn":    ["fn", "collect:ident", "(", ["sep0", "collect:ident", ","], ")", "collect:block"],
  "Let":   ["let", "collect:ident", "=", "%Expr", "@nl"],
  "Call":  ["collect:ident", "(", ["sep0", "%Expr", ","], ")", "@nl"],
  "Print": ["say", "%Expr", "@nl"]
}
```

---

## 3) layout — ควบคุมการขึ้นบรรทัดและการเยื้อง

```json
"layout": {
  "mode": "indent",
  "endline": "auto",
  "tabSize": 4,
  "tabMode": "stop",
  "indentUnit": 0,
  "mixedTabs": "allow",
  "lineBase": 1,
  "brackets": [["(",")"],["[","]"],["{","}"]],
  "continuation": "\\",
  "separators": [";"],
  "terminators": [],
  "multilineTypes": ["RAWSTRING"],
  "newlineType": "NEWLINE", "indentType": "INDENT", "dedentType": "DEDENT"
}
```

| ฟิลด์ | ค่า / ความหมาย |
|---|---|
| `mode` | `none` (ไม่แตะ token) · `newline` (แทรก NEWLINE) · `indent` (NEWLINE + INDENT/DEDENT แบบ Python) |
| `endline` | `auto` `lf` `crlf` `cr` หรือสตริงอะไรก็ได้ |
| `tabSize` / `tabMode` | `stop` = tab ปัดไป multiple ถัดไป (แบบ Python), `fixed` = tab = tabSize เสมอ |
| `indentUnit` | >0 = บังคับให้การเยื้องหารลงตัว |
| `mixedTabs` | `error` = ห้ามผสม tab กับ space |
| `lineBase` | เลขบรรทัดแรกที่ lexer ให้มา (0 หรือ 1) |
| `brackets` | ภายในวงเล็บไม่สร้าง NEWLINE/INDENT/DEDENT |
| `continuation` | token ท้ายบรรทัดที่แปลว่าต่อบรรทัด (`""` = ปิด) |
| `separators` | เช่น `;` → แปลงเป็น NEWLINE (ถ้าอยู่ท้ายบรรทัดจะถูกทิ้ง) |
| `terminators` | ตัวจบบรรทัด → เป็น NEWLINE เสมอ |
| `multilineTypes` | ชนิด token ที่ค่าอาจมีหลายบรรทัด (ใช้นับบรรทัดจบ) |

ทุก token จะมี `.indent` (ความกว้างการเยื้อง) และ `.level` (ระดับ block) และ `Nodes` มี `line`, `col`, `level`

**ภาษาแบบ Python:** ใช้ `"mode": "indent"` แล้วเขียน body ด้วย `collect:block`
**ภาษาแบบ C:** ใช้ `"mode": "newline"` หรือ `"none"` แล้วใช้ `collect:code` (`{ ... }`)

---

## 4) Composite collect — เก็บโครงสร้างอัตโนมัติ

| SPEC | อ่านอะไร | ได้อะไร |
|---|---|---|
| `collect:list` | `[a, b, c]` | `List` |
| `collect:array` | `[a, b, c]` | `Json.JsonArray` |
| `collect:map` | `{k: v}` (key เป็น ident/string/เลข) | `LinkedHashMap` |
| `collect:object` | `{"k": v}` (key เป็นสตริง) | `Json.JsonObject` |
| `collect:tuple` | `(a, b)` | `List` |
| `collect:value` | ค่าอัตโนมัติ: เลข/สตริง/bool/null/ident/list/map/tuple | ตามชนิด |
| `collect:json` | JSON ตรงๆ หรือ token สตริงที่เป็นข้อความ JSON | `JsonObject`/`JsonArray`/… |
| `collect:json:text` | บังคับตีความสตริงเป็น JSON | " |
| `collect:code` | `{ stmt stmt ... }` | `Nodes<Code>` (`nodes` = statements) |
| `collect:code:Name` | ใช้ delimiter จาก `grammar.blocks.Name` | " |
| `collect:raw` | `{ ... }` เก็บเป็นข้อความดิบ ไม่ parse | `String` |
| `collect:raw:Name` | ใช้ delimiter จาก `blocks.Name` | " |
| `collect:block` | แบบ Python: `NEWLINE INDENT stmts DEDENT` หรือ statement เดียวบรรทัดเดียวกัน | `Nodes<Block>` |

ค่าพวก `true/false/null` (และ `True/False/None/nil` เมื่อไม่ใช่ json mode) ถูกแปลงให้เอง

### grammar.auto — เปลี่ยนวงเล็บ/ตัวคั่น

```json
"auto": {
  "list":  ["[", "]"],
  "map":   ["{", "}"],
  "tuple": ["(", ")"],
  "code":  ["{", "}"],
  "sep":   [","],
  "pair":  [":"],
  "trailingComma": true,
  "rawJoin": " "
}
```

### grammar.blocks — block แบบตั้งชื่อ

```json
"blocks": { "Do": { "open": "do", "close": "end" } },
"statements": {
  "DoBlock": ["collect:code:Do"],
  "Asm":     ["asm", "collect:raw:Do"]
}
```

---

## 5) pratt — นิพจน์ที่มี precedence

```json
"pratt": {
  "Expr": {
    "primary": ["choice", "collect:number", "collect:string", "collect:ident"],
    "group":   ["(", ")"],
    "levels": [
      {"ops": ["or", "||"]},
      {"ops": ["and", "&&"]},
      {"ops": ["==", "!=", "<", ">", "<=", ">="]},
      {"ops": ["+", "-"]},
      {"ops": ["*", "/", "%"]},
      {"ops": ["**"], "assoc": "right"}
    ],
    "prefix": ["-", "!", "not"],
    "prefixPrec": 6,
    "postfix": [
      "++",
      {"name": "Call", "seq": ["(", ["sep0", "%Expr", ","], ")"]}
    ]
  }
}
```

- `levels` เรียง **ต่ำ → สูง** (index ยิ่งมากยิ่งผูกแน่น) ใส่เป็น array ตรงๆ หรือ `{ops, assoc}`
- `group` = วงเล็บครอบนิพจน์
- `prefix` → `Nodes<Unary>` (`Op`, `Right`) · `prefixPrec` ค่าเริ่มต้น = จำนวน levels
- `postfix` เป็นสตริง → `Nodes<Postfix>` · เป็น object → `Nodes{name, Left, args}` (เช่น `Call`)
- binary → `Nodes<Binary>` (`Left`, `Op`, `Right`)
- เรียกใช้ใน statement ด้วย `%Expr`

`Eval(node)` รองรับตัวดำเนินการ: `+ - * / % **`, เทียบ `== != < > <= >=`, `&& || and or`, unary `! not + -` และต่อสตริงด้วย `+`

## 6) exprs — rule ย่อยที่ใช้ซ้ำ

```json
"exprs": {
  "Args": ["(", ["sep0", "%Expr", ","], ")"],
  "Type": ["choice", "kw:Type", "collect:ident"]
},
"statements": {
  "Call": ["collect:ident", "%Args", "@nl"]
}
```

ผลของ rule ย่อย: 1 ค่า → ใส่ค่านั้นตรงๆ, หลายค่า → ใส่เป็น `List`

## 7) classes — กำหนดชุดตัวอักษรเอง

```json
"classes": {
  "Reg":   ["a", "b", "c"],
  "Ident": { "first": "[a-zA-Z_]", "rest": "[a-zA-Z0-9_]" },
  "Hex":   { "re": "0x[0-9a-fA-F]+" },
  "Alias": "[a-z]"
}
```

ใช้ใน grammar เป็น `$Reg` หรือ `class:Reg`

## 8) keywords และ tokenTypes

```json
"keywords": ["say", "fn", "let"],

"keywords": { "Type": ["int", "str"], "Ctl": ["if", "else"] },

"tokenTypes": { "ident": ["IDENT", "NAME"], "label": ["LABEL"] }
```

- keyword แบบ object แยกกลุ่มได้ → ใช้ `kw:Type`
- `grammar.keywordsIgnoreCase: true` ไม่สนตัวพิมพ์
- ถ้าไม่ใส่ `grammar.keywords` จะใช้ `lexer.keywords` (ถ้าเป็น array)
- `tokenTypes` ใช้บอกว่า type ไหนของ lexer นับเป็น `string/number/ident/...` (ค่าเริ่มต้นมี `STRING, NUMBER, INT, FLOAT, IDENT, KEYWORD, SYMBOL, BOOL, NULL, EOF` และชื่อใกล้เคียง) ชื่อใหม่ใช้เป็น `collect:label` / `type:label` ได้
- `ident` จะไม่ match คำที่เป็น keyword

---

## โครงสร้าง AST

```java
class Nodes {
    String name;          // ชื่อ statement / Binary / Unary / Postfix / Code / Block / ชื่อ postfix
    List<Object> args;    // ค่าที่ collect ได้
    List<Object> nodes;   // statements ข้างใน (Code / Block)
    Object Left, Op, Right;
    int line, col, level;
}
```

ค่าใน `args` อาจเป็น: `String`, `Long`, `Double`, `Boolean`, `null`, `List`, `Map`, `JsonObject`, `JsonArray`, `Nodes`, `Tok`, `Val`

`Val` = `{value, type, line, col}` ได้เมื่อใช้ `val:SPEC` หรือเปิด `"collectMeta": true` ใน grammar (ห่อทุกค่าที่ collect)

## ตัวอย่างเต็ม: ภาษาแบบ Python

```json
{
  "lexer": { },
  "layout": { "mode": "indent", "tabSize": 4 },
  "grammar": {
    "keywords": ["say", "fn", "if", "let"],
    "pratt": {
      "Expr": {
        "primary": ["choice", "collect:number", "collect:string", "collect:ident"],
        "group": ["(", ")"],
        "levels": [ {"ops": ["==", "<", ">"]}, {"ops": ["+", "-"]}, {"ops": ["*", "/"]} ],
        "prefix": ["-"],
        "postfix": [ {"name": "Call", "seq": ["(", ["sep0", "%Expr", ","], ")"]} ]
      }
    },
    "statements": {
      "Fn":  ["fn", "collect:ident", "(", ["sep0", "collect:ident", ","], ")", ":", "collect:block"],
      "If":  ["if", "%Expr", ":", "collect:block"],
      "Let": ["let", "collect:ident", "=", "%Expr", "@nl"],
      "Say": ["say", "%Expr", "@nl"]
    }
  }
}
```

โค้ดตัวอย่าง:

```
fn add(a, b):
    say a + b

if 1 < 2:
    say add(1, 2)
```

## Debug / แก้ปัญหา

- เปิด `DebugAst_Enable = true` ดูต้นไม้ AST ทันที
- ตำแหน่ง error ดูที่ `InLine บรรทัด:คอลัมน์` (ชี้ที่ token ที่ไกลที่สุดที่ parse ถึง)
- `Statement '...' consumed no tokens` → กฎ statement ไม่กิน token เลย (เช่นมีแต่ `opt`/`many`) ต้องเพิ่ม element ที่กินจริง
- `Grammar recursion too deep` → rule เรียกตัวเองวนโดยไม่กิน token (left recursion) ให้ใช้ `pratt` หรือ `many/sep` แทน
- `Unknown grammar rule '%X'` → ชื่อใน `%X` ไม่มีใน `pratt` / `exprs` / `statements`
- `Unknown class 'X'` / `Unknown block 'X'` → ยังไม่ประกาศใน `classes` / `blocks`
- `Bad regex` → regex ใน `re:` หรือ class ผิดไวยากรณ์ Java
- statement ที่ขึ้นต้นเหมือนกัน ให้เรียงอันที่ยาวกว่าไว้ก่อน เพราะ parser เลือกอันแรกที่ match
- ใน mode `indent`/`newline` อย่าลืม `@nl` ท้าย statement ที่ไม่ใช่ block

## ข้อจำกัดปัจจุบัน

- `Execute()` ยังเป็นโครง (รองรับแค่ `Print`) และถูกคอมเมนต์ไว้ใน `Statements()` — ตอนนี้ได้ AST อย่างเดียว
- ข้อผิดพลาดส่วนใหญ่เรียก `System.exit` แทนการ throw exception
- ความลึกของ rule/statement/value จำกัดที่ 256 ชั้น