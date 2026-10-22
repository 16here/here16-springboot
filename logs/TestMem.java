import java.lang.management.ManagementFactory;

public class TestMem {
    public static void main(String[] a) {
        Object b = ManagementFactory.getOperatingSystemMXBean();
        System.out.println("class=" + b.getClass().getName());
        try {
            Class<?> c = Class.forName("com.sun.management.OperatingSystemMXBean");
            System.out.println("com.sun.management available: " + c.isInstance(b));
            if (c.isInstance(b)) {
                Object mem = c.getMethod("getTotalPhysicalMemorySize").invoke(b);
                System.out.println("mem=" + mem);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
