package dayz.ber4j;

import java.net.InetSocketAddress;

public class Main {

    public static void main(String[] args) throws Exception {
        BattlEyeClient client = new BattlEyeClient(new InetSocketAddress("127.0.0.1", 2302));
        boolean success = client.login("changeme");
        System.out.println("login successful = " + success);
        client.disconnect();
    }
}
