public class TestStore {
  public static void main(String[] a) throws Exception {
    try {
      Class.forName("org.apache.rocketmq.store.StoreUtil");
      System.out.println("StoreUtil init OK, mem=" + org.apache.rocketmq.store.StoreUtil.getTotalPhysicalMemorySize());
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }
}
