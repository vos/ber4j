package arma.ber4j;

import java.net.InetSocketAddress;

public class Main {

    public static void main(String[] args) throws Exception {
        final BattlEyeClient client = new BattlEyeClient(new InetSocketAddress("127.0.0.1", 2302));

        client.addConnectionHandler(new ConnectionHandler() {
            @Override
            public void onConnected() {
                System.out.println("onConnected");
                try {
                    Thread.sleep(2000);
                    client.sendCommand(BattlEyeCommand.Say, "-1", "Here I am!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    Thread.sleep(5000);
                    client.sendCommand(BattlEyeCommand.Players);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConnectionFailed() {
                System.out.println("onConnectionFailed");
            }

            @Override
            public void onDisconnected(DisconnectType disconnectType) {
                System.out.println("onDisconnected: " + disconnectType);
            }
        });

        client.addCommandResponseHandler(new CommandResponseHandler() {
            @Override
            public void onCommandResponseReceived(String commandResponse, int id) {
                System.out.println("onCommandResponseReceived[" + id + "]: " + commandResponse);
            }
        });

        client.addMessageHandler(new MessageHandler() {
            @Override
            public void onMessageReceived(String message) {
                System.out.println("onMessageReceived: " + message);
            }
        });

        client.connect("changeme");

//        client.disconnect();
    }
}
