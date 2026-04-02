import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
public class test_tooltip {
    public static void main(String[] args) {
        Button.builder(Component.literal("test"), btn -> {}).tooltip(Tooltip.create(Component.literal("tt"))).build();
    }
}
