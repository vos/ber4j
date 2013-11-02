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
    private static final CRC32 CRC = new CRC32();

    private final InetSocketAddress host;
    private DatagramChannel datagramChannel;
    private ByteBuffer sendBuffer;
    private ByteBuffer receiveBuffer;
    private boolean connected;

    int sequenceNumber;
    private AtomicLong lastSent;
    private AtomicLong lastReceived;

    private final List<ConnectionHandler> connectionHandlerList;
    private final List<CommandResponseHandler> commandResponseHandlerList;
    private final List<MessageHandler> messageHandlerList;

    public BattlEyeClient(InetSocketAddress host) throws IOException {
        this.host = host;

        datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(true);
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
            // fire connection handler
            for (ConnectionHandler connectionHandler : connectionHandlerList) {
                connectionHandler.onConnected();
            }
        } else {
            for (ConnectionHandler connectionHandler : connectionHandlerList) {
                connectionHandler.onConnectionFailed();
            }
        }
        return connected;
    }

    public boolean isConnected() {
        return datagramChannel.isConnected() && connected;
    }

    public void disconnect() throws IOException {
        datagramChannel.disconnect();
        datagramChannel.close();
        datagramChannel = null;
        sendBuffer.clear();
        sendBuffer = null;
        receiveBuffer.clear();
        receiveBuffer = null;
        connected = false;
        // fire connection handler
        for (ConnectionHandler connectionHandler : connectionHandlerList) {
            connectionHandler.onDisconnected();
        }
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
        new Thread("ber4j receive data thread") {
            @Override
            public void run() {
                String[] multiPacketCache = null;
                int multiPacketCounter = 0;
                try {
                    while (isConnected()) {
                        readPacket();
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
                                // TODO should not happen!
                                break;
                        }
                    }
                } catch (IOException e) {
                    // TODO handle exception
                    e.printStackTrace();
                }
            }
        }.start();
    }

    // send empty command packet every 15 seconds to keep connection alive
    // TODO handle connection lost
    private void startMonitorThread() {
        new Thread("ber4j monitor thread") {
            @Override
            public void run() {
                final int waitTime = 15000;
                while (isConnected()) {
                    try {
                        Thread.sleep(waitTime);
                        if (System.currentTimeMillis() - lastSent.get() > waitTime) {
                            System.out.println("send empty command package");
                            createPacket(BattlEyePacketType.Command, getNextSequenceNumber(), null);
                            sendPacket();
                        }
                    } catch (InterruptedException | IOException e) {
                        // TODO log error
                        e.printStackTrace();
                    }
                }
            }
        }.start();
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
