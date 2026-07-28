# JVM ByteSwap Tool
![logo](sw-ico.png)

![actions](https://github.com/sunwu51/JVMByteSwapTool/actions/workflows/main.yml/badge.svg)

A tool that can hot swap the class byte code while jvm is running. Very suitable for `SpringBoot framework`.

Based on the jvm instrumentation tech, ASM, javassist and JVMTI.

# Usage
Download `swapper.jar` from the [release](https://github.com/sunwu51/JVMByteSwapTool/releases) page.

Make sure you have a JDK >= 1.8.
```bash
$ java -jar swapper.jar

// All of the java processes will be listed
// Choose the pid you want to attach
// Then the backend service will start
```

If you want to change the HTTP server port or WebSocket port:
```bash
$ java -jar -Dw_http_port=9999 -Dw_ws_port=19999 swapper.jar
```

## Web UI
After `swapper.jar` starts, visit `http://localhost:8000` and use the Web UI.

![image](https://i.imgur.com/WSKkrxX.png)


Now you can enjoy the functionalities of swapper tool.

For example, `Watch` some methods. Trigger this method, and then the params and return value and execution time cost will be printed. 

![image](https://i.imgur.com/RaEZ1w5.png)

It's `Watch` one of the functions provided by swapper tool.

Get more functions and details from the [wiki](https://github.com/sunwu51/JVMByteSwapTool/wiki).

## Online Debug
Online Debug adds bounded, non-suspending line breakpoints to already-loaded classes. It does not pause application threads. Each retained hit can show Parameters, Breakpoint Return / Throw, Instance fields, Static fields, Method Parameters & Method Return / Throw, and Call stack.

1. Open **Online Debug** in the Web UI and search for a loaded class.
2. Select the exact loaded class and inspect its decompiled runtime source.
3. Click the source gutter, then set TTL and maximum hits.
4. Trigger application traffic, select a breakpoint, then select a hit to load its details.
5. Select a hit to inspect its six detail sections; expand or collapse all sections, individual object trees, or the full call stack as needed.

Capture is intentionally shallow and rendered asynchronously. TTL is 60–3600 seconds and maximum hits are 1–100. Each hit is capped at 256 KiB, retained details share a 32 MiB budget, and older details can be evicted without changing historical hit counts.

![Online Debug](https://i.imgur.com/ouBvT21.png)

## TUI CLI
You can use the TUI client in the same way as the Web UI.

Install it from npm:
```bash
$ npm i -g jbs-client
$ jbs-client
```

Or download the standalone binary for your platform from the [swapper-tui releases](https://github.com/sunwu51/swapper-tui/releases), then run:
```bash
$ jbs-client
```

The interaction flow is consistent with the Web UI.

![img](https://i.imgur.com/5HTimiI.png)
