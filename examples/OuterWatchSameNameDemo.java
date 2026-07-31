import java.util.HashMap;
import java.util.Map;

public class OuterWatchSameNameDemo {

    public Integer sameOwnerSameMethod() {
        Map<String, Integer> first = new HashMap<>();
        Map<String, Integer> second = new HashMap<>();
        Map<String, Integer> third = new HashMap<>();
        return first.put("first", second.put("second", third.put("third", 3)));
    }

    public int differentOwnerSameName(User user, Product product) {
        return combine(user.getId(), product.getId());
    }

    private int combine(int userId, int productId) {
        return userId + productId;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Attach JVMByteSwapTool to PID " + ProcessHandle.current().pid());
        while (true) {
            Integer sameOwnerResult = new OuterWatchSameNameDemo().sameOwnerSameMethod();
            int differentOwnerResult = new OuterWatchSameNameDemo()
                    .differentOwnerSameName(new User(10), new Product(20));
            System.out.println("same owner result: " + sameOwnerResult
                    + ", different owner result: " + differentOwnerResult);
            Thread.sleep(1_000L);
        }
    }

    static class User {
        private final int id;

        User(int id) {
            this.id = id;
        }

        int getId() {
            return id;
        }
    }

    static class Product {
        private final int id;

        Product(int id) {
            this.id = id;
        }

        int getId() {
            return id;
        }
    }
}
