package dayz.ber4j;

public enum BattlEyePacketType {

    Login((byte) 0x00),
    Command((byte) 0x01),
    Acknowledge((byte) 0x02);

    private final byte type;

    BattlEyePacketType(byte type) {
        this.type = type;
    }

    public byte getType() {
        return type;
    }
}
