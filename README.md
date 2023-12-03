# JVM ByteSwap Tool
一个简单的jvm字节码替换工具，基于java instrumentation技术，使用javaassist库进行字节码替换。

该工具的开发初衷是`Alibaba arthas`不能解决工作中的一些问题，例如想要直接热替换类的时候，arthas总是报错编译错误，不能修改函数签名，或者不能增加函数，而实际上并没有增加函数。

此外arthas想要主动触发函数，尤其是spring的bean下的方法，需要复杂的操作流程，想要对其进行一些简化，所以有了这个小工具。

# requirement
- jdk >= 1.8
# start
从github release下载`swapper.jar`，运行指令，根据提示输入即可，如下：
```bash
$ java -jar swapper.jar
[0] 36200 swapper.jar
[1] 55908 com.example.springweb.SpringWebApplication
[2] Custom PID
>>>>>>>>>>>>Please enter the serial number
1
============The PID is 55908
>>>>>>>>>>>>Please enter the spring web server port, if not spring input enter key to skip
8080
============Attach finish
```
此时已经attach完成，到目标jvm的日志中可以看到如下log。

![image](https://i.imgur.com/y8v0ptc.png)

# usage
根据上面log中去请求页面，`http://localhost:8000` 默认是8000端口，如果出现冲突会替换，以上面log中为准。

![image](https://i.imgur.com/NurMQLM.png)

## 功能1：watch
监听函数的入参、返回值、耗时，打印并返回给页面展示，例如下面是`getUserById`方法的watch过程。

![gif](https://i.imgur.com/LUtOfEq.gif)

watch成功后，访问该方法，对应的日志在页面能收到。

## 功能2：changeBody
修改整个函数体，以返回字符串的hello函数为例
```java
@GetMapping("/hello")
public String hello() {
    return "hello";
}
```
![gif](https://i.imgur.com/VjDEHUb.gif)
## 功能3：execute
主动触发一段代码的执行，如果是spring项目，则提供`ctx`变量该变量为`springApplicationContext`，可以用`getBean("beanName")`获取bean对象。

这里以`userRepo`这个数据库的bean直接执行数据插入函数为例。

![gif](https://i.imgur.com/89yuNx5.gif)









