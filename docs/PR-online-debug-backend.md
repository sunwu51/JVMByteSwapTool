# PR：Online Debug 后端

## 标题

feat: add bounded online debug backend

## Summary

为已加载且可修改的 Java 类增加有界、非暂停的 Online Debug 能力。用户可以搜索类、查看运行时反编译源码、设置行断点、读取命中详情；业务线程不会被挂起，诊断异常不会改变业务返回值、异常或控制流。

## Changes

- 新增 Online Debug MCP tools 与 schema，支持类搜索、源码检查、断点管理、命中查询和历史清理。
- 新增 `DebugManager` 与 ASM 行断点转换，支持 ClassLoader 精确定位、TTL、命中上限，以及局部变量、调用结果、方法返回值和异常捕获。
- 新增异步有界命中存储，采集参数、字段、调用栈和对象浅快照，并通过单条、队列及总内存预算控制开销。
- 扩展 `DiagnosticBridge`、Global Transformer 生命周期和 SSE 调试事件，保持旧 listener 兼容及业务控制流不变。
- 增加原始字节码恢复能力，支持已预加载且不暴露 class resource 的隔离 ClassLoader。
- 补充 MCP、命中存储、Transformer、生命周期及断点语义测试。

## UI 截图

截图使用 README 中的 Imgur 外链：

![Online Debug](https://i.imgur.com/ouBvT21.png)

原始图片来源：[Imgur 相册](https://imgur.com/a/l28sRpx)。

## Related PR

- 前端 PR：[Online Debug frontend](https://github.com/sunwu51/JVMByteSwapTool/pull/REPLACE_WITH_FRONTEND_PR_NUMBER)
- 创建 PR 后，将占位符替换为实际 GitHub PR URL；前端 PR 中的后端链接同步替换。
