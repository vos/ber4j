package dayz.ber4j;

public enum BattlEyeCommand {

    Players("players"),
    Lock("#lock"),
    Unlock("#unlock");

    private final String commandString;

    BattlEyeCommand(String commandString) {
        this.commandString = commandString;
    }

    public String getCommandString() {
        return commandString;
    }
}
