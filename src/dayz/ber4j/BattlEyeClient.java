package dayz.ber4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class BattlEyeClient {
    private static final CRC32 CRC = new CRC32();

    private final InetSocketAddress host;
    private DatagramChannel datagramChannel;
    private ByteBuffer sendBuffer;
    private ByteBuffer receiveBuffer;
    private boolean connected;

    int sequenceNumber;
    private long lastSent;
    private long lastReceived;

    private final List<MessageReceivedHandler> messageHandlerList;

    public BattlEyeClient(InetSocketAddress host) throws IOException {
        this.host = host;

        datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(true);
        datagramChannel.bind(new InetSocketAddress(host.getPort()));

        sendBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_SNDBUF));
        sendBuffer.order(ByteOrder.LITTLE_ENDIAN); // ArmA 2 server uses little endian

        receiveBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_RCVBUF));
        receiveBuffer.order(sendBuffer.order());

        messageHandlerList = new ArrayList<>();
    }

    public boolean connect(String password) throws IOException {
        sequenceNumber = -1;
        lastSent = lastReceived = System.currentTimeMillis();

        datagramChannel.connect(host);

        createPacket(BattlEyePacketType.Login, -1, password);
        sendPacket();

        readPacket();
        // 0x00 | (0x01 (successfully logged in) OR 0x00 (failed))
        connected = receiveBuffer.get() == 0x00 && receiveBuffer.get() == 0x01;
        if (connected) {
            startReceivingData();
            startMonitorThread();
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
    }

    public boolean sendCommand(String command) throws IOException {
        if (!isConnected()) {
            return false;
        }
        createPacket(BattlEyePacketType.Command, getNextSequenceNumber(), command);
        sendPacket();
        return true;
    }

    public boolean sendCommand(BattlEyeCommand command, String... parameters) throws IOException {
        String commandString = command.getCommandString();
        for (String parameter : parameters) {
            commandString += ' ' + parameter;
        }
        return sendCommand(commandString);
    }

    public void addMessageHandler(MessageReceivedHandler handler) {
        messageHandlerList.add(handler);
    }

    public void removeMessageHandler(MessageReceivedHandler handler) {
        messageHandlerList.remove(handler);
    }

    public void removeAllMessageHandlers() {
        messageHandlerList.clear();
    }

    public List<MessageReceivedHandler> getAllMessageHandlers() {
        return messageHandlerList;
    }

    private void fireMessageHandler(String message, int id) {
        if (message == null || message.isEmpty()) {
            return;
        }
        for (MessageReceivedHandler messageReceivedHandler : messageHandlerList) {
            messageReceivedHandler.onMessageReceived(message, id);
        }
    }

    private void startReceivingData() {
        new Thread("ber4j receive data thread") {
            @Override
            public void run() {
                try {
                    while (isConnected()) {
                        readPacket();
                        byte packetType = receiveBuffer.get();
                        switch (packetType) {
                            case 0x00: {
                                System.out.println("multi packet response");
                                // TODO handle multi packet response
                                System.out.println(receiveBuffer);
                                break;
                            }
                            case 0x01: {
                                System.out.println("command response");
                                byte sn = receiveBuffer.get();
                                System.out.println("sequenceNumber = " + sn);
                                String message = getMessage();
                                fireMessageHandler(message, sn);
                                break;
                            }
                            case 0x02: {
                                System.out.println("server message");
                                byte sn = receiveBuffer.get();
                                System.out.println("sequenceNumber = " + sn);
                                String message = getMessage();
                                createPacket(BattlEyePacketType.Acknowledge, sn, null);
                                sendPacket();
                                fireMessageHandler(message, sn);
                                break;
                            }
                            default:
                                // TODO error?
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
    private void startMonitorThread() {
        final int waitTime = 15000;
        new Thread("ber4j monitor thread") {
            @Override
            public void run() {
                while (isConnected()) {
                    try {
                        Thread.sleep(waitTime);
                        if (System.currentTimeMillis() - lastSent > waitTime) {
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

    private String getMessage() {
        if (!receiveBuffer.hasRemaining()) {
            return "";
        }
        String message = new String(receiveBuffer.array(), receiveBuffer.position(), receiveBuffer.remaining());
        return message;
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
        lastSent = System.currentTimeMillis();
    }

    private void readPacket() throws IOException {
        receiveBuffer.clear();
        int read = datagramChannel.read(receiveBuffer);
//        System.out.println(read + " bytes read");
        receiveBuffer.flip();
        receiveBuffer.position(7); // skip header
        // TODO validate received packet?
        lastReceived = System.currentTimeMillis();
    }
}
