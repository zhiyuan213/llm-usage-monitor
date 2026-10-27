# MeterTrail console UI V2

Implemented from the supplied 1920 × 1080 design viewport and design specification on 2026-10-22.

- Shared Noto Sans SC font, bundled locally as WOFF2 with its OFL license.
- Sidebar 224 px; navigation 14 px with 18 px icons and 42 px rows.
- Page titles 24 px, section titles 16 px, body/controls 14 px, supporting text 12 px.
- Buttons 36 px high, minimum width 112 px; verification buttons at least 144 px. Table operations are text links, with destructive actions red.
- Cards 8 px radius, controls 6 px; primary #3156E8, sidebar #12213D, page #F5F7FB.
- Shared list toolbars, empty states and pagination; account details above two security panels; project details before project deletion; equal-height billing usage/history panels.
- Console-only styles live in frontend/src/console-v2.css. Public pages, backend endpoints, authorization, forms' submit handlers, filtering, pagination sizes and export content remain unchanged.

Validation: screenshots of all ten supplied page layouts at 1920 × 1080, with no horizontal page overflow; default five-row overview, settings and billing fit one screen. 390 px responsive checks cover management, traces, alerts, account and billing. Browser checks cover pagination 10/2 rows, empty filtering, trace details, project shortcuts and analytics dimensions. Actual data and empty states remain dynamic, not copied from the reference screenshots.

## 官网 V2（首页、价格、开发文档）

- 按官网 V2 设计统一 1280px 内容宽度、72px 导航、36px 按钮、颜色与页脚。
- 2026-10-25：页脚退款右侧增加“开发工具”（Developer tools）外链，新标签页打开 https://basaltkit.com/，沿用现有链接样式。
- 首页采用双栏文案与平直预览卡片，四列功能区及底部行动入口。
- 价格页保留所有套餐价格、配额、保留期及分段超额规则，统一套餐卡片与说明表格。
- 文档快速开始改为两列编号步骤卡片，代码区域独立滚动；保留目录、语言示例及复制逻辑。
- 样式独立在 public-v2.css，控制台 V2 不受影响。移动端自动折行。
- 验证：前端 5 项测试、生产构建、依赖审计、生成示例同步及 Node/脚本 11 项测试通过；浏览器检查三页及 390px 布局。内嵌浏览器未返回剪贴板内容，复制功能仅确认原逻辑未修改。

## 后续页面更新入口

当前持续维护的样式规范见 [页面样式规范](ui-style-guide.md)。根目录 `AGENTS.md` 已要求后续前端页面修改先阅读并遵守该规范。本文保留 V2 实施与验证记录。

2026-10-25 新增费用诊断、发布对比、自动周报项目入口；共用指标、发现建议、区间对比及费用分组组件。详情见 [运营分析功能说明](business-insights.md)。周报自愿订阅，默认关闭。

2026-10-25 新增预算提醒和失败与慢调用，保留 V2 工具栏、分页和窄屏内部表格滚动；周报增加手动发送入口。见 [预算与调用分析说明](operational-insights.md)。

2026-10-23 工作空间概览按用户参考图调整为头像/名称/套餐标识与四张浅色指标卡，保持实际数据和项目操作；响应式四列、两列、单列，账期保留UTC精度。

2026-10-23 后续调整：开发文档代码块取消内部滚动和高度上限，长行自动换行，统一使用正文外层滚动。

2026-10-23 支付接入：新增官网 V2 支付链接页面 `/pay`，复用卡片、按钮和双语提示；收银台使用 Paddle 官方窗口，套餐授权仍以后端回调为准。

- 2026-10-23 文档表格：桌面字段表随正文宽度换行，不设1000px最小宽度；正文与右侧滚动条保留24px间距和稳定滚动槽。窄屏右留白16px，六列表格保留720px可读宽度及必要横向滚动，不裁切内容。

- 2026-10-23 登录页底部“创建账号、忘记密码、验证邮箱”使用同一行链接组，字号/字重/行高一致（桌面14px，窄屏12px），空间不足时整项换行，不拆分链接文字。


## 2026-10-27 控制台可用性补充

- 调用记录在 760px 以下转为带字段标签的卡片；桌面保留表格，ID 缩略且可复制。其他宽表只在容器内滚动，不把英文单词逐字拆开。
- 快捷日期选择后立即查询，自定义日期保留查询按钮；数据统计自然年/月筛选保持原有点击查询行为。
- 分页默认 25 条，选项 10/25/50/100，记忆选择；独立列表各自分页。空列表隐藏翻页区但保留导出。
- 新增接入暂停在正文顶部说明原因和账单入口；不将拒绝新增对象等同于数据丢失。
- 新建密钥只在当前显示期间提供 cURL/Node/Python 示例，密钥不写入浏览器存储；保留验证码流程。
- 无定价调用的指标用横线和明确原因，不展示零费用。详细统计口径允许展开阅读；保留收费、安全及数据不完整的重要提醒。


## 2026-10-27 首页性能与首次接入

- 英文公开页使用系统字体，中文使用自托管 CJK 子集与系统回退；font-display: swap，unicode-range 避免拉丁文本触发 CJK 字体下载。子集由 scripts/subset-font.py 生成，原字体及许可证保留。
- 静态 assets 的 JS/CSS/SVG 启用 gzip 并带 Vary；账号/API 响应不在此范围。HTTP/2 配置属于公网 TLS 入口，不能通过内部 HTTP Nginx 配置冒充已验证。
- 首页首屏保留免费额度与说明入口，units 示例结构表移到价格页；不制造客户数字、推荐语、认证或固定正常的状态页。
- 快速开始直接链接可独立运行的 Node/Python/REST 教程，不要求克隆仓库；免费开始仍进入注册。
- 480px 以下导航折叠为可展开菜单；产品入口定位功能区，价格页 units 链接定位说明表。
- 统计同意面板放在页尾文档流，关闭即不授权，统计偏好随时可打开；未授权前不加载 GA。
- 安全页只描述已有措施，保留数据保留和非独立认证说明。运营者信息、正式政策本轮按用户要求暂不补，不能仅删除政策占位提示冒充已完成。

- 2026-10-27：首页套餐摘要卡片必须保留桌面32px/窄屏20px内边距，文案与操作分区，主要按钮和辅助链接统一排列；教程卡片标题不额外留顶空，卡片末尾入口对齐。案例演示入口居中，窄屏正常换行。
