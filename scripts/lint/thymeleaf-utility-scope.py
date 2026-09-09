#!/usr/bin/env python3
"""
扫「Thymeleaf utility 写在 ${} 外面」这一类写法。

为什么值得一条独立扫描器:
  #lists.isEmpty(x) 这类 utility 必须【整段写在 ${} 内】。写在外面时 Thymeleaf 直接
  「Could not parse as expression」—— 而它是【渲染期】才炸:编译过、单测过、启动过,
  响应会截断在出错那一行,用户拿到半截页面。

  memory feedback_thymeleaf_diagnosis 里早写着这条规则,v1.21 开发时还是踩了
  (类目页第一个大类那行,beta 上一点就半截)。只写在记忆里的规则会被再踩一次,
  所以做成机器扫描。
"""
import re, glob

bad = []
for p in glob.glob('src/main/resources/templates/**/*.html', recursive=True):
    s = open(p).read()
    for m in re.finditer(r'th:[a-zA-Z-]+="([^"]*)"', s, re.S):
        v = m.group(1)
        for um in re.finditer(r'#(lists|numbers|strings|temporals|maps|sets|bools|objects)\.', v):
            pre = v[:um.start()]
            # utility 之前的 ${ 已经全部闭合 → 说明它落在 ${} 外面
            if pre.count('${') <= pre.count('}'):
                bad.append(f"{p}: {' '.join(v.split())[:70]}")
                break

if bad:
    print("BAD")
    for b in bad[:20]:
        print("  " + b)
else:
    print("CLEAN")
