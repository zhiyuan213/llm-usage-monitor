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
- 首页采用双栏文案与平直预览卡片，四列功能区及底部行动入口。
- 价格页保留所有套餐价格、配额、保留期及分段超额规则，统一套餐卡片与说明表格。
- 文档快速开始改为两列编号步骤卡片，代码区域独立滚动；保留目录、语言示例及复制逻辑。
- 样式独立在 public-v2.css，控制台 V2 不受影响。移动端自动折行。
- 验证：前端 5 项测试、生产构建、依赖审计、生成示例同步及 Node/脚本 11 项测试通过；浏览器检查三页及 390px 布局。内嵌浏览器未返回剪贴板内容，复制功能仅确认原逻辑未修改。

## 后续页面更新入口

当前持续维护的样式规范见 [页面样式规范](ui-style-guide.md)。根目录 `AGENTS.md` 已要求后续前端页面修改先阅读并遵守该规范。本文保留 V2 实施与验证记录。

2026-10-23 后续调整：开发文档代码块取消内部滚动和高度上限，长行自动换行，统一使用正文外层滚动。

2026-10-23 支付接入：新增官网 V2 支付链接页面 `/pay`，复用卡片、按钮和双语提示；收银台使用 Paddle 官方窗口，套餐授权仍以后端回调为准。
