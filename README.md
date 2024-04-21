# JVM ByteSwap Tool
![logo](sw-ico.png)

A tool that can hot swap the class byte code while jvm is running. Very suitable for `SpringBoot framework`.

Based on the jvm instrumentation tech, javassist lib, JVMTI.

# Usage
Download the zip file from the [release](https://github.com/sunwu51/JVMByteSwapTool/releases) page.

Make sure you have a JDK >= 1.8.
```bash
$ unzip swapper-<latest-version>.zip

$ java -jar swapper.jar

// All of the java processes will be listed in following
// Choose the pid you want to attach
// Then a web ui will be served at http://localhost:8000
```

Visit this url `http://localhost:8000` then you will get the following Web UI.

![image](https://i.imgur.com/peQ5O2V.png)

Now you can enjoy the functionalities of swapper tool. 

For example, `Watch` some methods

![image](https://i.imgur.com/JGW0JCv.png)

Trigger this method, and then the params and return value and execution time cost will be printed. 

![image](https://i.imgur.com/olYyxnh.png)

It's `Watch` one of the functions provided by swapper tool.

Get more functions and details from the [wiki](https://github.com/sunwu51/JVMByteSwapTool/wiki).