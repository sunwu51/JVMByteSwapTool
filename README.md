# JVM ByteSwap Tool
一个简单的jvm字节码替换工具，基于java instrumentation技术，使用javassist库进行字节码替换。 

集中解决日常debug的过程中，最常遇到的一些痛点：
- jar包运行时查看函数入参和返回值。
- 直接修改方法体内容。
- 立即触发一段代码，通常是spring bean的一个函数。
- 替换整个类的字节码

【使用须知】如果你的项目是spring boot项目或者没有复杂的类加载器的普通项目，那么这个工具适合你。

如果是具有非常错综复杂的类加载器的庞大项目，那么这个工具可能并不能正常运行。

# requirement
- jdk >= 1.8
# start
从github release下载`swapper-xx.jar`，运行指令，根据提示输入即可，如下：
```bash
# java >=9 
$ java -jar swapper.jar

# java == 8 Linux/MacOs:
$ java -cp ${JAVA_HOME}/lib/tools.jar:swapper.jar w.Attach
# java == 8 Windows
$ java -cp "%JAVA_HOME%\lib\tools.jar";swapper.jar w.Attach


[0] 36200 swapper.jar
[1] 55908 com.example.springweb.SpringWebApplication
[2] Custom PID
>>>>>>>>>>>>Please enter the serial number
1
============The PID is 55908
============Attach finish
```
此时已经attach完成，到目标jvm的日志中可以看到如下log。

![image](https://i.imgur.com/y8v0ptc.png)

# usage
根据上面log中去请求页面，`http://localhost:8000` 默认是8000端口，如果出现冲突会替换，以上面log中为准。

![image](https://i.imgur.com/NurMQLM.png)

然后根据页面提示就可以对jvm进行修改操作。

注意：
- 在java8 + springboot2下进行的测试，其他版本理论上是兼容，如果有问题可以issue反馈。









