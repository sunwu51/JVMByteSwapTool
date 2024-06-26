package w.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
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
}
