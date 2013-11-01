package dayz.ber4j;

import java.net.InetSocketAddress;

public class Main {

    public static void main(String[] args) throws Exception {
        BattlEyeClient client = new BattlEyeClient(new InetSocketAddress("127.0.0.1", 2302));
        boolean success = client.connect("changeme");
        System.out.println("login successful = " + success);

        client.addMessageHandler(new MessageReceivedHandler() {
            @Override
            public void onMessageReceived(String message, int id) {
                System.out.println("message received: " + message);
            }
        });

        Thread.sleep(2000);
        client.sendCommand(BattlEyeCommand.Lock);

        Thread.sleep(5000);
        client.sendCommand(BattlEyeCommand.Players);

//        client.disconnect();
    }
}
