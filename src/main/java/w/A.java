package w;

/**
 * @author Frank
 * @date 2023/11/25 13:29
 */
public class A {
    public static void main(String[] args) throws InterruptedException {
        A a = new A();
        while (true){
            a.run1((Math.floor(Math.random() * 10)) + "");
            Thread.sleep(1000L);
        }

//        Class s = Object.class;
//        System.out.println(s.getSuperclass());

    }
    public void run() {
        System.out.println("A.run");
    }

    public String run1(String a) {
        System.out.println("A.run1" + a);
        return "res==" +a;
    }
}
