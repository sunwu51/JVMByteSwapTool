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

注意！！ 工具中所有的类名，只能是类，不能是接口。
## 1 watch
watch作用是对函数环绕增强，打印出入参和函数耗时

用法为填写方法签名，格式为`包名.类名#方法名`，点击watch按钮，即进入监听模式，当方法被调用时，会触发在右侧打印相关日志。
```java
@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {
    @Autowired
    TestService testService;
    
    // 使用watch监控以下方法 testService.test1内部实现是返回 input x 5
    @GetMapping("/test1")
    public int test1(int input) {
        int a = testService.test1(input);
        int b = testService.test1(input);
        return a + b;
    }
}
// 这是service的代码
@Service
public class TestService {
    public int test1(int input) {
        return input * 5;
    }
}
```
![image](https://i.imgur.com/3dtuGkw.png)

## 2 outer-watch
OuterWatch同样是监控方法，但是角度不同，是一个方法调用另一个方法时，对后者的监控。

用法为分别填写外部方法、内部方法的签名，例如上面代码中`TestController#test1`方法中调用了`TestService#test1`，想要知道每次调用`TestService#test1`的出入参。

![image](https://i.imgur.com/8kR0kxD.png)

对于`TestService#test1`有两次调用分别打印了出来，并标注了行号。相比于直接watch监控`TestService#test1`，这样做的优势是提高了精度，尤其对于一些在很多地方都会被调用的方法，优势明显。

在OuterWatch的内部方法匹配中引入了通配符，即上图中`com.example.demo.TestService#test1`可以改为`*#test1`。
## 3 change-body
ChangeBody的作用是替换整个方法的方法体，用法与watch类似，只不过需要指定详细的参数类型（无参的函数不填，多个参数用逗号隔开，类型是全限定类名）。

例如原来service.test1的实现是inputx5，所以之前返回值是5+5=10，下图中我们将其改为inputx50，故而得到结果是100.
```java
public int test1(int input) {
    int a = testService.test1(input);
    int b = testService.test1(input);
    return a + b;
}
```
![image](https://i.imgur.com/kmHisRJ.png)

注意`int`是基础类型，不需要写包名，全限定类名就是`int`，如果参数是`Integer`，那么这里需要写`java.lang.Integer`。

## 4 outer-change
OuterChange与OuterWatch的思路类似，都是方法A调用方法B的情况下，对方法B的操作。

如下代码`/test1`和`/test2`作用相同。
```java
@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {
    @Autowired
    TestService testService;
    
    @GetMapping("/test1")
    public int test1(int input) {
        int a = testService.test1(input);
        int b = testService.test1(input);
        return a + b;
    }

    @GetMapping("/test2")
    public int test2(int input) {
        int a = testService.test1(input);
        int b = testService.test1(input);
        return a + b;
    }
}
```

如下使用方式，我们将`com.example.demo.TestController#test1`方法体中调用`*#test1`（这里的里方法也可以用通配符），即所有调用`testService.test1`的代码，进行了修改，不再调用底层方法，而是直接修改调用结果为1.因为controller最后返回两次调用相加所以返回2。作为对照组test2的返回仍为10。
![image](https://i.imgur.com/6xDPTdC.png)

## 5 execute
execute的作用是直接触发一段java代码的执行，注意类名需要用全限定类名(java.lang和java.util下除外，提前引入了)。

这里必须一提的是提前注入的方法和变量，方便调试：
- 1 `ctx`变量，如果当前为`SpringBoot`项目，会在当前执行上下文中注入`ctx`变量，是`Spring`的`ApplicationContext`，可以通过ctx.getBean("testService")获取bean触发一些函数。
- 2 `w.Global.info(Object obj)`打印`obj.toString()`，并同时在web控制台上打印。如果是基础类型不能直接打印，受限于javassist编译器简陋，需要自行封包，例如`w.Global.info(new Integer(123))`
- 3 `w.Global.ognl(String exp, Object root)`，执行ognl表达式，如果是spring项目则会将ognl的类加载器设置为springboot的类加载器，更加方便。
- 4 `w.Global.beanTarget(Object bean)`，如果是被spring增强的bean对象，则返回增强前的target对象。否则返回本身。

demo1 使用info打印随机UUID：

![image](https://i.imgur.com/jfezuMB.png)

demo2 使用ctx调用spring的相关bean：

![image](https://i.imgur.com/nRFhy47.png)

demo3 用ognl表达式简化上面代码：

![image](https://i.imgur.com/6wM1OPg.png)

demo4 如果ognl表达式需要用多个外部对象传入，而不只是root，则可以借助对象数组：

![image](https://i.imgur.com/qiQJWTS.png)

逗号分隔多段，表达更清晰。

![image](https://i.imgur.com/utCeiP2.png)

demo5 如果想要查看bean中某个field的值，或者调用非public方法，要套一层`w.Global.beanTarget`，来避免调用到增强对象，虽然不是所有的bean都会被增强，但是该方法在非增强对象的情况下会返回本身，所以最好每次访问field或者private方法都套上。
```java
@Service
public class TestService {
    private final String MY_CONST = "MY_CONST";
    //...
}
```
## 6 replace-class
替换类的class文件，用法比较直观，修改代码，编译后得到新的class文件，上传即可替换，注意不要新增或删除方法，修改schema等操作。

原来返回10的页面，现在返回了20，因为底层的test1方法从x5改为x10了。

![image](https://i.imgur.com/eSkqTJt.png)


## 7 others
以上功能都是通过jvm retransform实现的字节码的替换，retransform可以指定多个transformer，例如在`watch`一个方法后，还可以对方法进行`changeBody`等其他操作，这些transformer的改动，会按照添加顺序，依次对类进行修改，作用是链式的。

可以通过effected class按钮查看当前被修改的类，也可以指定uuid剔除某些transformer，或者reset删除全部。

![image](https://github.com/sunwu51/JVMByteSwapTool/assets/15844103/3144aab1-c6a6-4df2-9737-6f0e503b36a2)




