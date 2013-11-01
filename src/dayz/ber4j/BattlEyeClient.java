package dayz.ber4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.zip.CRC32;

public class BattlEyeClient {
    private static final CRC32 CRC = new CRC32();

    private final InetSocketAddress host;
    private DatagramChannel datagramChannel;
    private ByteBuffer sendBuffer;
    private ByteBuffer receiveBuffer;
    private boolean connected;

    public BattlEyeClient(InetSocketAddress host) throws IOException {
        this.host = host;

        datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(true); // debug
        datagramChannel.bind(new InetSocketAddress(host.getPort()));

        sendBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_SNDBUF));
        sendBuffer.order(ByteOrder.LITTLE_ENDIAN); // ArmA 2 server uses little endian

        receiveBuffer = ByteBuffer.allocate(datagramChannel.getOption(StandardSocketOptions.SO_RCVBUF));
        receiveBuffer.order(sendBuffer.order());
    }

    // 0x00 | (0x01 (successfully logged in) OR 0x00 (failed))
    public boolean login(String password) throws IOException {
        datagramChannel.connect(host);

        createPacket(BattlEyePacketType.Login, (byte) 0, password);
        sendPacket();

        readPacket();
        connected = receiveBuffer.get(7) == 0x00 && receiveBuffer.get(8) == 0x01;
        if (connected) {
            // TODO start receiving data
        }
        return connected;
    }

    public boolean isConnected() {
        return connected;
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

    // 'B'(0x42) | 'E'(0x45) | 4-byte CRC32 checksum of the subsequent bytes | 0xFF
    private void createPacket(BattlEyePacketType type, byte sequenceNumber, String command) {
        sendBuffer.clear();
        sendBuffer.put((byte) 0x42); // B
        sendBuffer.put((byte) 0x45); // E
        sendBuffer.position(6); // skip checksum
        sendBuffer.put((byte) 0xFF);
        sendBuffer.put(type.getType());

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
        System.out.println(write + " bytes written");
    }

    private void readPacket() throws IOException {
        receiveBuffer.clear();
        int read = datagramChannel.read(receiveBuffer);
        System.out.println(read + " bytes read");
        receiveBuffer.flip();
        // TODO validate received packet?
    }
}
