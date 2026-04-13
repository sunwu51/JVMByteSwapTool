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

## TUI CLI
你也可以使用 TUI 客户端，交互逻辑与 Web UI 一致。

可以直接通过 npm 安装：
```bash
$ npm i -g jbs-client
$ jbs-client
```

或者从 [jbs-client-opentui releases](https://github.com/sunwu51/jbs-client-opentui/releases) 下载对应平台的 TUI 二进制可执行文件，然后运行：
```bash
$ jbs-client
```

![img](https://i.imgur.com/5HTimiI.png)
