package arma.ber4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public class BattlEyeClient {
    private static final Logger log = LoggerFactory.getLogger(BattlEyeClient.class);

    private static final int MONITOR_INTERVAL = 1000;
    private static final int TIMEOUT_DELAY = 5000;
    private static final int KEEP_ALIVE_DELAY = 30000;
    private static final int RECONNECT_DELAY = 2000;

    private final CRC32 CRC = new CRC32(); // don't share with other clients

    private final InetSocketAddress host;
    private DatagramChannel datagramChannel;
    private ByteBuffer sendBuffer;
    private ByteBuffer receiveBuffer;

    private AtomicBoolean connected;
    private boolean autoReconnect = true;

    private String password;
    int sequenceNumber;
    private AtomicLong lastSent;
    private AtomicLong lastReceived;

    private Thread receiveDataThread;
    private Thread monitorThread;

    private final Queue<Command> commandQueue;
    private boolean emptyCommandQueueOnConnect;

    private final List<ConnectionHandler> connectionHandlerList;
    private final List<CommandResponseHandler> commandResponseHandlerList;
    private final List<MessageHandler> messageHandlerList;

    public BattlEyeClient(InetSocketAddress host) throws IOException {
        this.host = host;
        connected = new AtomicBoolean(false);

        commandQueue = new ConcurrentLinkedQueue<>();
        emptyCommandQueueOnConnect = true;

        connectionHandlerList = new ArrayList<>();
        commandResponseHandlerList = new ArrayList<>();
        messageHandlerList = new ArrayList<>();
    }

    public boolean connect(String password) throws IOException {
        log.trace("connecting to {}", host);
        if (isConnected()) {
            return false;
        }
        this.password = password;

        datagramChannel = DatagramChannel.open();
//        datagramChannel.configureBlocking(true); // remove?
        datagramChannel.bind(new InetSocketAddress(0));

        sendBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_SNDBUF));
        sendBuffer.order(ByteOrder.LITTLE_ENDIAN); // ArmA 2 server uses little endian

        receiveBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_RCVBUF));
        receiveBuffer.order(sendBuffer.order());

        sequenceNumber = -1;
        long time = System.currentTimeMillis();
        lastSent = new AtomicLong(time);
        lastReceived = new AtomicLong(time);

        if (emptyCommandQueueOnConnect) {
            commandQueue.clear();
        }

        datagramChannel.connect(host);

        startReceivingData();
        startMonitorThread();

        createPacket(BattlEyePacketType.Login, -1, password);
        sendPacket();

        return true;
    }

    public boolean reconnect() throws IOException {
        return connect(password);
    }

    public boolean isConnected() {
        return datagramChannel != null && datagramChannel.isConnected() && connected.get();
    }

    public void disconnect() throws IOException {
        if (isConnected()) {
            doDisconnect(DisconnectType.Manual);
        }
    }

    private void doDisconnect(DisconnectType disconnectType) throws IOException {
        log.trace("disconnecting from {}", host);
        connected.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        if (receiveDataThread != null) {
            receiveDataThread.interrupt();
            receiveDataThread = null;
        }
        if (datagramChannel != null) {
            datagramChannel.disconnect();
            datagramChannel.close();
            datagramChannel = null;
        }
        sendBuffer = null;
        receiveBuffer = null;
        // fire ConnectionHandler.onDisconnected
        for (ConnectionHandler connectionHandler : connectionHandlerList) {
            connectionHandler.onDisconnected(disconnectType);
        }
        if (disconnectType == DisconnectType.ConnectionLost && autoReconnect) {
            // wait before reconnect
            new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                        reconnect();
                    } catch (InterruptedException e) {
                        log.warn("auto reconnect thread interrupted");
                    } catch (IOException e) {
                        log.error("error while trying to reconnect", e);
                    }
                }
            }.start();
        }
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public int sendCommand(String command) throws IOException {
        log.debug("sendCommand: {}", command);
        if (!isConnected()) {
            return -1;
        }
        Command cmd = new Command(command);
        if (commandQueue.offer(cmd)) {
            cmd.id = getNextSequenceNumber();
        } else {
            log.debug("command queue is full");
            return -2;
        }
        if (commandQueue.size() == 1) {
            // enqueue and send this command immediately
            createPacket(BattlEyePacketType.Command, cmd.id, command);
            sendPacket();
        } else {
            // only enqueue this command
            log.trace("command enqueued: {}", cmd);
        }
        return cmd.id;
    }

    public int sendCommand(BattlEyeCommand command, String... params) throws IOException {
        StringBuilder commandBuilder = new StringBuilder(command.getCommandString());
        for (String param : params) {
            commandBuilder.append(' ');
            commandBuilder.append(param);
        }
        return sendCommand(commandBuilder.toString());
    }

    private void sendNextCommand(int id) throws IOException {
        Command command = commandQueue.poll();
        if (command == null) {
            log.error("command queue empty");
            return;
        }
        if (command.id != id) {
            log.warn("invalid command id");
        }
        if (!commandQueue.isEmpty()) {
            command = commandQueue.peek();
            log.trace("send enqueued command: {}", command);
            createPacket(BattlEyePacketType.Command, command.id, command.command);
            sendPacket();
        }
    }

    public boolean isEmptyCommandQueueOnConnect() {
        return emptyCommandQueueOnConnect;
    }

    public void setEmptyCommandQueueOnConnect(boolean b) {
        emptyCommandQueueOnConnect = b;
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

    private void fireCommandResponseHandler(String commandResponse, int id) {
        // also send empty command response
        for (CommandResponseHandler commandResponseHandler : commandResponseHandlerList) {
            commandResponseHandler.onCommandResponseReceived(commandResponse, id);
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
                log.trace("start receive data thread");
                String[] multiPacketCache = null; // use separate cache for every sequence number? possible overlap?
                int multiPacketCounter = 0;
                try {
                    while (!isInterrupted()) {
                        if (!readPacket() || receiveBuffer.remaining() < 2) {
                            log.warn("invalid data received");
                            continue;
                        }
                        if (this != receiveDataThread) {
                            log.debug("instance thread changed (receive data thread)");
                            break; // exit thread
                        }
                        byte packetType = receiveBuffer.get();
                        switch (packetType) {
                            case 0x00: {
                                // login response
                                // 0x00 | (0x01 (successfully logged in) OR 0x00 (failed))
                                if (receiveBuffer.remaining() != 1) {
                                    log.error("unexpected login response received");
                                    doDisconnect(DisconnectType.ConnectionFailed);
                                    return; // exit thread
                                }
                                connected.set(receiveBuffer.get() == 0x01);
                                if (connected.get()) {
                                    log.debug("connected to {}", host);
                                    // fire ConnectionHandler.onConnected()
                                    for (ConnectionHandler connectionHandler : connectionHandlerList) {
                                        connectionHandler.onConnected();
                                    }
                                } else {
                                    log.debug("connection failed to {}", host);
                                    doDisconnect(DisconnectType.ConnectionFailed);
                                    return; // exit thread
                                }
                                break;
                            }
                            case 0x01: {
                                // command response
                                // 0x01 | received 1-byte sequence number | (possible header and/or response (ASCII string without null-terminator) OR nothing)
                                byte sn = receiveBuffer.get();
                                if (receiveBuffer.hasRemaining()) {
                                    if (receiveBuffer.get() == 0x00) {
                                        // multi packet response
                                        log.trace("multi packet command response received: {}", sn);
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
                                            fireCommandResponseHandler(sb.toString(), sn);
                                            sendNextCommand(sn);
                                        }
                                    } else {
                                        // single packet response
                                        log.trace("single packet command response received: {}", sn);
                                        // position -1 and remaining +1 because the call to receiveBuffer.get() increments the position!
                                        String commandResponse = new String(receiveBuffer.array(), receiveBuffer.position() - 1, receiveBuffer.remaining() + 1);
                                        fireCommandResponseHandler(commandResponse, sn);
                                        sendNextCommand(sn);
                                    }
                                } else {
                                    log.trace("empty command response received: {}", sn);
                                }
                                break;
                            }
                            case 0x02: {
                                // server message
                                // 0x02 | 1-byte sequence number (starting at 0) | server message (ASCII string without null-terminator)
                                byte sn = receiveBuffer.get();
                                log.trace("server message received: {}", sn);
                                String message = new String(receiveBuffer.array(), receiveBuffer.position(), receiveBuffer.remaining());
                                createPacket(BattlEyePacketType.Acknowledge, sn, null);
                                sendPacket();
                                fireMessageHandler(message);
                                break;
                            }
                            default:
                                // should not happen!
                                log.warn("invalid packet type received: {}", packetType);
                                break;
                        }
                    }
                } catch (IOException e) {
                    if (e instanceof ClosedByInterruptException) {
                        log.trace("receive data thread interrupted");
                    } else {
                        log.error("unhandled exception while receiving data", e);
                    }
                }
                log.trace("exit receive data thread");
            }
        };
        receiveDataThread.start();
    }

    private void startMonitorThread() {
        monitorThread = new Thread("ber4j monitor thread") {
            @Override
            public void run() {
                log.trace("start monitor thread");
                try {
                    while (!isInterrupted()) {
                        Thread.sleep(MONITOR_INTERVAL);
                        if (this != monitorThread) {
                            log.debug("instance thread changed (monitor thread)");
                            break; // exit thread
                        }
                        if (lastSent.get() - lastReceived.get() > TIMEOUT_DELAY) {
                            log.debug("connection to server lost");
                            doDisconnect(DisconnectType.ConnectionLost);
                            break; // exit thread
                        }
                        if (System.currentTimeMillis() - lastSent.get() > KEEP_ALIVE_DELAY) {
                            // send empty command packet to keep the connection alive
                            log.trace("send empty command packet");
                            createPacket(BattlEyePacketType.Command, getNextSequenceNumber(), null);
                            sendPacket();
                        }
                    }
                } catch (InterruptedException e) {
                    log.trace("monitor thread interrupted");
                } catch (IOException e) {
                    log.error("unhandled exception in monitor thread", e);
                }
                log.trace("exit monitor thread");
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
        sendBuffer.put((byte) 'B');
        sendBuffer.put((byte) 'E');
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
        log.trace("{} bytes written to the channel", write);
        lastSent.set(System.currentTimeMillis());
    }

    private boolean readPacket() throws IOException {
        receiveBuffer.clear();
        int read = datagramChannel.read(receiveBuffer);
        log.trace("{} bytes read from the channel", read);
        if (read < 7) {
            log.warn("invalid header size");
            return false;
        }
        receiveBuffer.flip();
        if (receiveBuffer.get() != (byte) 'B' || receiveBuffer.get() != (byte) 'E') {
            log.warn("invalid header");
            return false;
        }
        int checksum = receiveBuffer.getInt();
        if (receiveBuffer.get() != (byte) 0xFF) {
            log.warn("invalid header");
            return false;
        }
        // TODO validate received packet
        lastReceived.set(System.currentTimeMillis());
        return true;
    }

    private static class Command {
        public final String command;
        public int id = -1;

        public Command(String command) {
            this.command = command;
        }

        @Override
        public String toString() {
            return "Command{" +
                    "command='" + command + '\'' +
                    ", id=" + id +
                    '}';
        }
    }
}
