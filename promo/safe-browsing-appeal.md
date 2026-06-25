<!-- Google Search Console「Deceptive pages(社会工程)」误判申诉 · 中英双语 · 直接粘到 Request Review 框 -->
<!-- 适用:dixi-token.top(站点拥有者私有实例)+ beta.dixi-token.top(公开合成数据 Demo)整域被误判 -->
<!-- 建议:先上线 v0.9 公开落地页(根路径不再是裸登录)再点 Request Review,过审率更高 -->

## 中文

您好,

被标记为「Deceptive pages / 欺骗性网页」的两个站点 `dixi-token.top` 与 `beta.dixi-token.top`,是同一个开源项目的两份部署。这是误判,说明如下:

- 这是一款**自托管、开源**的「家庭资产管理 / 记账」工具,源码完全公开、可逐行审计:
  https://github.com/LuoDi-Nate/financial-management （Apache-2.0 协议,非商业)。
- `dixi-token.top` 是**站点拥有者本人**的私有实例,登录页仅供其本人访问自己服务器上的私有数据;
  `beta.dixi-token.top` 是**公开演示环境,数据全部为合成的假数据**,供他人试用开源项目,使用一次性共享演示账号。
- 它**不诱导任何人安装软件,不套取任何个人/第三方信息**,不仿冒任何品牌、银行或第三方登录页,无恶意软件、无欺诈或误导内容。
- 误判的可能诱因:根路径此前直接跳转到登录页(首屏即登录框),加之域名含 “token” 字样、使用 .top 顶级域,触发了反钓鱼模型对「陌生域名上的登录页」的启发式判定。

针对此,我已为根路径上线**公开介绍落地页**(说明软件用途、功能截图、开源仓库地址、自托管与隐私说明),首屏不再是裸登录框。恳请复核并移除「Deceptive pages」警告。非常感谢!

## English

Hello,

The two sites flagged as "Deceptive pages", `dixi-token.top` and `beta.dixi-token.top`, are two deployments of the same open-source project. This is a false positive:

- It is a **self-hosted, open-source** family-finance / personal-accounting tool. The full source is public and auditable, line by line:
  https://github.com/LuoDi-Nate/financial-management (Apache-2.0, non-commercial).
- `dixi-token.top` is the **owner's own private instance**; its login page is used solely by the owner to access their own private data on their own server.
  `beta.dixi-token.top` is a **public demo populated entirely with synthetic, fake sample data** so others can try the open-source project, using throwaway shared demo credentials.
- It **does not trick anyone into installing software and does not solicit any personal or third-party information.** It impersonates no brand, bank, or third-party sign-in page, and contains no malware, fraud, or misleading content.
- Likely trigger of the false positive: the root path previously redirected straight to a login form (a login box on first paint); combined with the word "token" in the domain and the .top TLD, this matched anti-phishing heuristics for "a login page on an unfamiliar domain".

To address this, I have published a **public landing page** at the root (explaining the software's purpose, feature screenshots, the open-source repository, and self-hosting/privacy notes); the first paint is no longer a bare login form. Please re-review and remove the "Deceptive pages" warning. Thank you very much!
