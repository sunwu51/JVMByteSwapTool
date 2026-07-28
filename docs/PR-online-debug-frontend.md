# PR：Online Debug 前端

## 标题

feat: add online debug workspace

## Summary

配合后端 Online Debug MCP 能力，在现有 Web UI 增加独立 workspace。保留原有 Tools workspace，同时提供类搜索、运行时反编译源码、断点、Hits 和命中详情查看。

## Changes

- 新增 Online Debug workspace，并保持现有 Tools workspace 与 WebSocket 连接逻辑不变。
- 对接后端 Online Debug MCP tools，支持类搜索、ClassLoader 选择、源码检查、断点管理、Hits 查询和历史清理。
- 新增 Decompiled source 视图，支持可执行行提示、源码字号调整及 gutter/快捷键断点操作。
- 新增断点配置与状态展示，包括 TTL、最大命中数、行号调整、倒计时和捕获状态。
- 新增命中详情，展示 Parameters、Breakpoint Return / Throw、Instance fields、Static fields、Method Parameters & Method Return / Throw 和 Call stack。
- 支持详情整体/分区/对象树折叠、过滤、Object/JSON 视图和大屏查看，并限制详情区域滚动，避免撑高页面。
- 过滤 Online Debug SSE 事件，补充断线恢复、状态竞态保护及相关 UI 测试。

## UI 截图

![Online Debug](https://i.imgur.com/ouBvT21.png)

README 使用同一截图外链，原始图片来源：[Imgur 相册](https://imgur.com/a/l28sRpx)。

## Related PR

- 后端 PR：[Online Debug backend](https://github.com/sunwu51/JVMByteSwapTool/pull/REPLACE_WITH_BACKEND_PR_NUMBER)
- 创建 PR 后，将占位符替换为实际 GitHub PR URL；后端 PR 中的前端链接同步替换。
