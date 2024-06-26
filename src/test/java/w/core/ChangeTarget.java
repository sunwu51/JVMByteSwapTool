package w.core;

import lombok.Data;
import w.Global;

import java.io.IOException;

@Data
public class ChangeTarget implements Runnable {
    int age;
    String name;

    public String toString() {
        return "toString";
    }

    public double add(int a, double b) throws IOException, InterruptedException {
        return a + b;
    }


    @Override
    public void run() {
        System.out.println("run");
    }
}