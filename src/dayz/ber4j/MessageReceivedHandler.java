package dayz.ber4j;

public interface MessageReceivedHandler {
    void onMessageReceived(String message, int id);
}
