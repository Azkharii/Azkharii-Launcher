package com.cinematic.tvlauncher;
import com.cinematic.tvlauncher.navigation.PinnedWheelSlotOrder;
import java.util.Arrays;
import java.util.List;
public final class PinnedWheelSlotOrderTest {
    public static void runTests() {
        List<String> slots=Arrays.asList("A","B","C","D","E");
        assert PinnedWheelSlotOrder.rotate(slots,"N",1).equals(Arrays.asList("B","C","D","E","N"));
        assert PinnedWheelSlotOrder.rotate(slots,"N",-1).equals(Arrays.asList("N","A","B","C","D"));
        assert "A".equals(PinnedWheelSlotOrder.outgoing(slots,1));
        assert "E".equals(PinnedWheelSlotOrder.outgoing(slots,-1));
        assert slots.equals(Arrays.asList("A","B","C","D","E"));
        System.out.println("  [PASS] PinnedWheelSlotOrderTest: 5 assertions passed.");
    }
}