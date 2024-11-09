# JVM ByteSwap Tool
![logo](sw-ico.png)

![actions](https://github.com/sunwu51/JVMByteSwapTool/actions/workflows/main.yml/badge.svg)

这是一个能在jvm运行时热替换类的字节码的工具，特别适合`Spring Boot`框架，它基于`instrumentation` `ASM` `javassist` `JVMTI`等技术。

# 用法
从[release](https://github.com/sunwu51/JVMByteSwapTool/releases)下载jar包，并确保运行环境是`jdk8+`
```bash
$ java -jar swapper.jar

// 所有的java进程会被列出
// 选择你要attach的jvm进程
// 然后一个webui就会提供在 http://localhost:8000
```

打开`http://localhost:8000`你会看到下面的页面，当然如果你想更改端口，可以通过下面启动指令：
```bash
$ java -jar -Dw_http_port=9999 -Dw_ws_port_19999 swapper.jar
```

![image](https://i.imgur.com/WSKkrxX.png)

现在你就可以体验`swapper`提供的各种功能了，例如`watch`某个方法，触发这个方法的时候，入参返回值和耗时将会被打印出来。

![image](https://i.imgur.com/RaEZ1w5.png)

这是众多功能之一的`watch`，想要查看更多信息可以查看[wiki](https://github.com/sunwu51/JVMByteSwapTool/wiki).
