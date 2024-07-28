package w.core;

import w.core.asm.Tool;
import w.core.model.WatchTransformer;
import w.util.RequestUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * @author Frank
 * @date 2024/6/24 22:39
 */
public class WatchTarget implements Runnable {
    String name;
    int age;

    public void empty() {}

    public void voidMethodWithNoParams() {
        System.out.println("voidMethodWithNoParams");
    }

    public double doubleMethodWithParams(double... params) {
        System.out.println("doubleMethodWithParams");
        return params[0];
    }

    @Override
    public String toString() {
        System.out.println("toString");
        return "name: " + name + ", age: " + age;
    }

    @Override
    public void run() {
        System.out.println("run");
    }

    public String getName() {
        return name;
    }


    public int getAge() {
        return age;
    }


    public static List<String> readFile(String path) throws IOException {
        return Files.readAllLines(Paths.get(path));
    }

    public void tryCatchTest(String input) {
        try {
            System.out.println(Integer.parseInt(input));
        } catch (Throwable e) {
            System.out.println("error");
            throw e;
        } finally {
            System.out.println("finally");

        }
    }

    private double add(long a, Integer b, double c) {
        return a + b + c;
    }
    private int div(double a, double b) {
        return (int) a / (int) b ;
    }

    public void subMethodCall() {
        System.out.println("age=" + getAge());
        System.out.println(add(1, 2, 3));
        System.out.println(div(2, 0));
    }

    public void subMethodCallExp() throws IOException {
        readFile("123");
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public String callManyMethod() {
        for (int i = 0; i < 10; i++) {
            sleep(1);
            System.out.println("name=" + getName());
        }
        for (int i = 0; i < 5; i++) {
            sleep(3);
            System.out.println(add(1,1, 3));
        }
        return "Hello World";
    }

    public int fib(int n) {
        if (n <= 2) return 1;
        return fib(n - 1) + fib(n - 2);
    }

    public int[] arrayReturn1() {
        return new int[]{1,2};
    }


    public String[] arrayReturn2() {
        return new String[]{"1"};
    }

    public int ow1(int a, int b) {
        return ow1(a + b);
    }

    public int ow1(int c) {
        return (int) add(c, 0, 1);
    }
}
