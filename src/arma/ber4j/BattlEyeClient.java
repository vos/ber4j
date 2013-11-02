package arma.ber4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public class BattlEyeClient {
    private static final int KEEP_ALIVE_DELAY = 30_000;
    private static final int TIMEOUT_DELAY = 5_000;

    private final CRC32 CRC = new CRC32(); // don't share with other clients

    private final List<ConnectionHandler> connectionHandlerList;
    private final List<CommandResponseHandler> commandResponseHandlerList;
    private final List<MessageHandler> messageHandlerList;

    private final InetSocketAddress host;
    private final DatagramChannel datagramChannel;
    private ByteBuffer sendBuffer;
    private ByteBuffer receiveBuffer;

    private boolean connected;
    private boolean autoReconnect = true;

    private String password;
    int sequenceNumber;
    private AtomicLong lastSent;
    private AtomicLong lastReceived;

    private Thread receiveDataThread;
    private Thread monitorThread;

    public BattlEyeClient(InetSocketAddress host) throws IOException {
        this.host = host;

        datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(true); // remove?
        datagramChannel.bind(new InetSocketAddress(host.getPort()));

        sendBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_SNDBUF));
        sendBuffer.order(ByteOrder.LITTLE_ENDIAN); // ArmA 2 server uses little endian

        receiveBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_RCVBUF));
        receiveBuffer.order(sendBuffer.order());

        connectionHandlerList = new ArrayList<>();
        commandResponseHandlerList = new ArrayList<>();
        messageHandlerList = new ArrayList<>();
    }

    public boolean connect(String password) throws IOException {
        if (isConnected()) {
            return false;
        }
        this.password = password;

        sequenceNumber = -1;
        long time = System.currentTimeMillis();
        lastSent = new AtomicLong(time);
        lastReceived = new AtomicLong(time);

        datagramChannel.connect(host);

        createPacket(BattlEyePacketType.Login, -1, password);
        sendPacket();

        readPacket();
        // 0x00 | (0x01 (successfully logged in) OR 0x00 (failed))
        connected = receiveBuffer.get() == 0x00 && receiveBuffer.get() == 0x01;
        if (connected) {
            startReceivingData();
            startMonitorThread();
            // fire ConnectionHandler.onConnected()
            for (ConnectionHandler connectionHandler : connectionHandlerList) {
                connectionHandler.onConnected();
            }
        } else {
            // fire ConnectionHandler.onConnectionFailed()
            for (ConnectionHandler connectionHandler : connectionHandlerList) {
                connectionHandler.onConnectionFailed();
            }
        }
        return connected;
    }

    public boolean reconnect() throws IOException {
        return connect(password);
    }

    public boolean isConnected() {
        return datagramChannel.isConnected() && connected;
    }

    public void disconnect() throws IOException {
        if (isConnected()) {
            doDisconnect(DisconnectType.Manual);
        }
    }

    private void doDisconnect(DisconnectType disconnectType) throws IOException {
        datagramChannel.disconnect();
//        datagramChannel.close();
        sendBuffer = null;
        receiveBuffer = null;
        connected = false;
        // fire ConnectionHandler.onDisconnected
        for (ConnectionHandler connectionHandler : connectionHandlerList) {
            connectionHandler.onDisconnected(disconnectType);
        }
        receiveDataThread.interrupt();
        receiveDataThread = null;
        monitorThread.interrupt();
        monitorThread = null;
        if (disconnectType == DisconnectType.ConnectionLost && autoReconnect) {
            reconnect();
        }
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public boolean sendCommand(String command) throws IOException {
        if (!isConnected()) {
            return false;
        }
        createPacket(BattlEyePacketType.Command, getNextSequenceNumber(), command);
        sendPacket();
        return true;
    }

    public boolean sendCommand(BattlEyeCommand command, String... params) throws IOException {
        String commandString = command.getCommandString();
        for (String param : params) {
            commandString += ' ' + param;
        }
        return sendCommand(commandString);
    }

    public List<ConnectionHandler> getAllConnectionHandlers() {
        return connectionHandlerList;
    }

    public boolean addConnectionHandler(ConnectionHandler handler) {
        return connectionHandlerList.add(handler);
    }

    public boolean removeConnectionHandler(ConnectionHandler handler) {
        return connectionHandlerList.remove(handler);
    }

    public void removeAllConnectionHandlers() {
        connectionHandlerList.clear();
    }

    public List<CommandResponseHandler> getCommandRespondHandlers() {
        return commandResponseHandlerList;
    }

    public boolean addCommandResponseHandler(CommandResponseHandler handler) {
        return commandResponseHandlerList.add(handler);
    }

    public boolean removeCommandResponseHandler(CommandResponseHandler handler) {
        return commandResponseHandlerList.remove(handler);
    }

    public void removeAllCommandResponseHandlers() {
        commandResponseHandlerList.clear();
    }

    public List<MessageHandler> getAllMessageHandlers() {
        return messageHandlerList;
    }

    public boolean addMessageHandler(MessageHandler handler) {
        return messageHandlerList.add(handler);
    }

    public boolean removeMessageHandler(MessageHandler handler) {
        return messageHandlerList.remove(handler);
    }

    public void removeAllMessageHandlers() {
        messageHandlerList.clear();
    }

    private void fireCommandResponseHandler(String commandResponse) {
        if (commandResponse == null || commandResponseHandlerList.isEmpty()) {
            return;
        }
        for (CommandResponseHandler commandResponseHandler : commandResponseHandlerList) {
            commandResponseHandler.onCommandResponseReceived(commandResponse);
        }
    }

    private void fireMessageHandler(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        for (MessageHandler messageHandler : messageHandlerList) {
            messageHandler.onMessageReceived(message);
        }
    }

    private void startReceivingData() {
        receiveDataThread = new Thread("ber4j receive data thread") {
            @Override
            public void run() {
                String[] multiPacketCache = null; // use separate cache for every sequence number? possible overlap?
                int multiPacketCounter = 0;
                try {
                    while (isConnected()) {
                        readPacket();
                        if (this != receiveDataThread) {
                            // instance thread changed
                            return; // terminate this thread
                        }
                        byte packetType = receiveBuffer.get();
                        switch (packetType) {
                            case 0x01: {
                                // command response
                                // 0x01 | received 1-byte sequence number | (possible header and/or response (ASCII string without null-terminator) OR nothing)
                                byte sn = receiveBuffer.get();
                                if (receiveBuffer.hasRemaining()) {
                                    if (receiveBuffer.get() == 0x00) {
                                        // multi packet response
                                        // 0x00 | number of packets for this response | 0-based index of the current packet
                                        byte packetCount = receiveBuffer.get();
                                        byte packetIndex = receiveBuffer.get();
                                        if (multiPacketCounter == 0) {
                                            // first packet received
                                            multiPacketCache = new String[packetCount];
                                        }
                                        multiPacketCache[packetIndex] = new String(receiveBuffer.array(), receiveBuffer.position(), receiveBuffer.remaining());
                                        if (++multiPacketCounter == packetCount) {
                                            // last packet received
                                            // merge packet data
                                            StringBuilder sb = new StringBuilder(1024 * packetCount); // estimated size
                                            for (String commandResponsePart : multiPacketCache) {
                                                sb.append(commandResponsePart);
                                            }
                                            multiPacketCache = null;
                                            multiPacketCounter = 0;
                                            fireCommandResponseHandler(sb.toString());
                                        }
                                    } else {
                                        // single packet response
                                        // position -1 and remaining +1 because the call to receiveBuffer.get() increments the position!
                                        String message = new String(receiveBuffer.array(), receiveBuffer.position() - 1, receiveBuffer.remaining() + 1);
                                        fireCommandResponseHandler(message);
                                    }
                                }
                                // else: empty command response
                                break;
                            }
                            case 0x02: {
                                // server message
                                // 0x02 | 1-byte sequence number (starting at 0) | server message (ASCII string without null-terminator)
                                byte sn = receiveBuffer.get();
                                String message = new String(receiveBuffer.array(), receiveBuffer.position(), receiveBuffer.remaining());
                                createPacket(BattlEyePacketType.Acknowledge, sn, null);
                                sendPacket();
                                fireMessageHandler(message);
                                break;
                            }
                            default:
                                // should not happen!
                                break;
                        }
                    }
                } catch (IOException e) {
                    // TODO handle exception
                    e.printStackTrace();
                }
            }
        };
        receiveDataThread.start();
    }

    private void startMonitorThread() {
        monitorThread = new Thread("ber4j monitor thread") {
            @Override
            public void run() {
                while (isConnected()) {
                    try {
                        Thread.sleep(1000);
                        if (this != monitorThread) {
                            // instance thread changed
                            return; // terminate this thread
                        }
                        if (lastSent.get() - lastReceived.get() > TIMEOUT_DELAY) {
                            doDisconnect(DisconnectType.ConnectionLost);
                            return; // terminate this thread
                        }
                        if (System.currentTimeMillis() - lastSent.get() > KEEP_ALIVE_DELAY) {
                            // send empty command package to keep the connection alive
                            createPacket(BattlEyePacketType.Command, getNextSequenceNumber(), null);
                            sendPacket();
                        }
                    } catch (IOException e) {
                        // TODO log error
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        return; // terminate this thread
                    }
                }
            }
        };
        monitorThread.start();
    }

    private int getNextSequenceNumber() {
        sequenceNumber = sequenceNumber == 255 ? 0 : sequenceNumber + 1;
        return sequenceNumber;
    }

    // 'B'(0x42) | 'E'(0x45) | 4-byte CRC32 checksum of the subsequent bytes | 0xFF
    private void createPacket(BattlEyePacketType type, int sequenceNumber, String command) {
        sendBuffer.clear();
        sendBuffer.put((byte) 0x42); // B
        sendBuffer.put((byte) 0x45); // E
        sendBuffer.position(6); // skip checksum
        sendBuffer.put((byte) 0xFF);
        sendBuffer.put(type.getType());

        if (sequenceNumber >= 0) {
            sendBuffer.put((byte) sequenceNumber);
        }

        if (command != null && !command.isEmpty()) {
            byte[] payload = command.getBytes();
            sendBuffer.put(payload);
        }

        CRC.reset();
        CRC.update(sendBuffer.array(), 6, sendBuffer.position() - 6);
        int checksum = (int) CRC.getValue();
        sendBuffer.putInt(2, checksum);

        sendBuffer.flip();
    }

    private void sendPacket() throws IOException {
        int write = datagramChannel.write(sendBuffer);
//        System.out.println(write + " bytes written");
        lastSent.set(System.currentTimeMillis());
    }

    private void readPacket() throws IOException {
        receiveBuffer.clear();
        int read = datagramChannel.read(receiveBuffer);
//        System.out.println(read + " bytes read");
        receiveBuffer.flip();
        // TODO validate received packet
        receiveBuffer.position(7); // skip header
        lastReceived.set(System.currentTimeMillis());
    }
}
