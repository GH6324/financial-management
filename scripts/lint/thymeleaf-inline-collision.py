#!/usr/bin/env python3
"""扫模板里「JS 字面量撞上 Thymeleaf 内联标记」的写法。

Thymeleaf 在 <script> 块里默认开 JavaScript 内联,`[[...]]` 与 `[(...)]` 是它的表达式标记。
JS 自己的**嵌套数组字面量** `[['a','b'], ...]` 恰好以 `[[` 开头 —— 于是被当成表达式解析,
**渲染期**才失败,而且是在响应头发出之后:响应被截断成一片空白,连错误页都没有。

为什么必须机器扫:
  · 编译期、单测、启动全过 —— 模板不参与编译;
  · **curl 也过** —— 200 + 完整字节数 + 内容 grep 得到,因为 curl 不校验 chunked 终止符;
  · 只有真浏览器会报 ERR_INCOMPLETE_CHUNKED_ENCODING。
判据:<script> 块(未标 th:inline="none")里出现 `[[` / `[(`,且**后面不是合法的表达式起头**。
Thymeleaf 的表达式不只有 `${}` —— 还有 `@{}`(链接)`#{}`(消息)`*{}`(选择)`~{}`(片段),
项目里 `[[@{/admin/family/logo}]]` 就是正确写法。第一版判据只放过 `${`,把这两处报成了红
(本版第四次把护栏绑在过窄的特征上 —— 判据写完必须**两个方向**都验:坏的报红、好的不报)。
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2] / 'src/main/resources/templates'
SCRIPT = re.compile(r'<script\b([^>]*)>(.*?)</script>', re.S | re.I)
EXPR = r'[$@#*~]\{'          # Thymeleaf 五种表达式起头
BAD = re.compile(r'\[\[(?!\s*' + EXPR + r')|\[\((?!\s*' + EXPR + r')')

bad = []
for f in sorted(ROOT.rglob('*.html')):
    src = f.read_text(encoding='utf-8')
    for m in SCRIPT.finditer(src):
        attrs, body = m.group(1), m.group(2)
        if 'th:inline="none"' in attrs or "th:inline='none'" in attrs:
            continue
        for hit in BAD.finditer(body):
            line = src[:m.start(2) + hit.start()].count('\n') + 1
            frag = body[hit.start():hit.start() + 40].replace('\n', ' ')
            bad.append(f"{f.relative_to(ROOT.parents[3])}:{line}: {frag}")

if bad:
    print("模板里有 JS 字面量撞 Thymeleaf 内联标记(渲染期截断响应,curl 查不出):")
    for b in bad:
        print("  " + b)
    print('修法:给该 <script> 加 th:inline="none"。')
    sys.exit(1)
print("CLEAN")
