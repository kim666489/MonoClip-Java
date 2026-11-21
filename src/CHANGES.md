# Clip — สิ่งที่แก้

**MonoLexer.java**
1. `line`/`col` ของ token เป็น 1-based และชี้ที่ *ตัวอักษรแรก* ของ token (เดิม 0-based และชี้ท้าย token) -> ตรงกับ `layout.lineBase` default = 1, error `InLine L:C` ถูกบรรทัด
2. Maximal Munch จาก config: operator/symbol หลายตัวอักษร (`**` `&&` `||` `->` `/=` ...) ใช้ได้
3. `/` อ่านจาก config (ไม่ hardcode DIV) และ `/=` ใช้ได้ (comment `//` `/*` ยังมาก่อน)
4. section `keyword`/`operator`/`symbol` ใน lexer ไม่บังคับแล้ว: ไม่ใส่ = ใช้ค่า default; `keyword` เป็น array ได้
5. ตัวเลข: `3else` -> INT 3 + IDENT, `1..2`/`1.foo` ไม่ถูกกิน `.`
6. char literal รองรับ escape `'\n'` `'\''` `'\\'` `'\0'`

**MonoClip.java**
7. `DefRule` มี rule จริง (let/say + Pratt) -> `loadRuleDef()` และ `LoadDefRuleIfRuleFileNotFound_Enable` ใช้ได้
8. `lexer` ใน rule เป็น optional

**MonoTools.java**
9. template (`gen`) ใช้ `val:` ที่ primary ของ Expr -> AST แยก `ident` กับ `string` (`Val<ident>` vs `Val<string>`)
10. `validate`/`check` ตรวจส่วน `lexer` และ `check` โหลด lexer config จริงด้วย

**หมายเหตุ:** rule ที่เคยตั้ง `layout.lineBase: 0` เพื่อหนีบั๊กเดิม ให้ลบออก (หรือตั้งเป็น 1)
