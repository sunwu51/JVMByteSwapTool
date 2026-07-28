# JVM ByteSwap Tool
![logo](sw-ico.png)

![actions](https://github.com/sunwu51/JVMByteSwapTool/actions/workflows/main.yml/badge.svg)

这是一个能在jvm运行时热替换类的字节码的工具，特别适合`Spring Boot`框架，它基于`instrumentation` `ASM` `javassist` `JVMTI`等技术。

# 用法
从 [release](https://github.com/sunwu51/JVMByteSwapTool/releases) 下载 `swapper.jar`，并确保运行环境是 `jdk8+`
```bash
$ java -jar swapper.jar

// 所有的 Java 进程会被列出
// 选择你要 attach 的 JVM 进程
// 然后后端服务会启动
```

如果你想更改 HTTP 端口或 WebSocket 端口，可以这样启动：
```bash
$ java -jar -Dw_http_port=9999 -Dw_ws_port=19999 swapper.jar
```

## Web UI
启动 `swapper.jar` 后，打开 `http://localhost:8000` 即可使用 Web UI。

![image](https://i.imgur.com/WSKkrxX.png)

现在你就可以体验`swapper`提供的各种功能了，例如`watch`某个方法，触发这个方法的时候，入参返回值和耗时将会被打印出来。

![image](https://i.imgur.com/RaEZ1w5.png)

这是众多功能之一的`watch`，想要查看更多信息可以查看[wiki](https://github.com/sunwu51/JVMByteSwapTool/wiki).

## Online Debug
在线调试可以为已加载类设置有界、非暂停的行断点，不会暂停业务线程。每条保留命中可展示 Parameters、Breakpoint Return / Throw、Instance fields、Static fields、Method Parameters & Method Return / Throw、Call stack 六个详情区块。

1. 在 Web UI 中打开 **Online Debug**，搜索已加载类。
2. 选择准确的已加载类，查看运行时字节码反编译源码。
3. 点击源码 gutter，设置 TTL 与最大命中次数。
4. 触发业务请求，选择断点，再选择命中以按需加载详情。
5. 选择命中查看六个详情区块；可全展开/全折叠，也可单独折叠对象树或展开完整调用栈。

捕获采用浅复制并异步渲染。TTL 为 60–3600 秒，最大命中次数为 1–100。单条命中上限为 256 KiB，全部保留详情共享 32 MiB 预算；淘汰旧详情不会改变历史命中计数。

![Online Debug](https://i.imgur.com/ouBvT21.png)

## TUI CLI
你也可以使用 TUI 客户端，交互逻辑与 Web UI 一致。

可以直接通过 npm 安装：
```bash
$ npm i -g jbs-client
$ jbs-client
```

或者从 [swapper-tui releases](https://github.com/sunwu51/swapper-tui/releases) 下载对应平台的 TUI 二进制可执行文件，然后运行：
```bash
$ jbs-client
```

![img](https://i.imgur.com/5HTimiI.png)
