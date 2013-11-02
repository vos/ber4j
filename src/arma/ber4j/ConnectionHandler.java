package arma.ber4j;

public interface ConnectionHandler {
    void onConnected();
    void onConnectionFailed();
    void onDisconnected(DisconnectType disconnectType);
}
