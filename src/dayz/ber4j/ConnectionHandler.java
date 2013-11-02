package dayz.ber4j;

public interface ConnectionHandler {
    void onConnected();
    void onConnectionFailed();
    void onDisconnected();
    void onConnectionLost();
}
