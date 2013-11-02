package arma.ber4j;

public interface CommandResponseHandler {
    void onCommandResponseReceived(String commandResponse, int id);
}
