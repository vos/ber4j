package arma.ber4j;

public interface ConnectionHandler {
    void onConnected();
    void onDisconnected(DisconnectType disconnectType);
}
