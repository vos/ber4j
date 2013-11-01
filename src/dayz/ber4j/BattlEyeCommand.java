package dayz.ber4j;

public enum BattlEyeCommand {

    Players("players"),
    Lock("#lock"),
    Unlock("#unlock");

    private final String command;

    BattlEyeCommand(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }
}
