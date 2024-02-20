package net.nadar.timedrankup;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.text.Text;

import java.io.PrintStream;

public class SystemOutCommandOutput implements CommandOutput {
    private final PrintStream outputStream;

    public SystemOutCommandOutput(PrintStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void sendMessage(Text message) {
        outputStream.println(message.getString());
    }

    @Override
    public boolean shouldReceiveFeedback() {
        return true;
    }

    @Override
    public boolean shouldTrackOutput() {
        return true;
    }

    @Override
    public boolean shouldBroadcastConsoleToOps() {
        return false;
    }
}