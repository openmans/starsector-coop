package coop.wingman;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code MethodHandles}-based {@link ReflectiveCoopWingmanApi} without touching
 * {@code java.lang.reflect.Method} (which Starsector's script classloader blocks at runtime).
 */
class ReflectiveCoopWingmanApiTest {

    public static final class FakeWingmanVoidApi {
        static boolean joined;
        static String name = "PilotA";
        static boolean throwOnOpen;
        static int openCalls;
        static List<String> pumpLines = List.of();
        static int[] playerIds = new int[0];
        static final List<String[]> reserved = new ArrayList<>();

        public static boolean isJoined() { return joined; }
        public static String localName() { return name; }
        public static void openHost() {
            openCalls++;
            if (throwOnOpen) {
                throw new IllegalStateException("port busy");
            }
        }
        public static List<String> pump() { return pumpLines; }
        public static int[] players() { return playerIds; }
        public static String playerName(int id) { return id == 1 ? "GuestOne" : "GuestTwo"; }
        public static void reserveShip(String playerName, String memberId) {
            reserved.add(new String[]{playerName, memberId});
        }
    }

    public static final class FakeWingmanBooleanApi {
        static boolean openResult = true;
        public static boolean openHost() { return openResult; }
    }

    public static final class EmptyApi {
    }

    @BeforeEach
    void resetFakes() {
        FakeWingmanVoidApi.joined = false;
        FakeWingmanVoidApi.name = "PilotA";
        FakeWingmanVoidApi.throwOnOpen = false;
        FakeWingmanVoidApi.openCalls = 0;
        FakeWingmanVoidApi.pumpLines = List.of();
        FakeWingmanVoidApi.playerIds = new int[0];
        FakeWingmanVoidApi.reserved.clear();
        FakeWingmanBooleanApi.openResult = true;
    }

    @Test
    void bindsAndInvokesAllSevenWingmanMethodsViaMethodHandles() {
        ReflectiveCoopWingmanApi api = new ReflectiveCoopWingmanApi(FakeWingmanVoidApi.class, () -> true);
        assertTrue(api.wingmanEnabled());
        assertFalse(api.isJoined());
        FakeWingmanVoidApi.joined = true;
        assertTrue(api.isJoined());
        assertEquals("PilotA", api.localName());
        assertTrue(api.openHost());
        assertEquals(1, FakeWingmanVoidApi.openCalls);

        FakeWingmanVoidApi.pumpLines = List.of("GuestOne joined", "", "Ready");
        assertEquals("GuestOne joined\nReady", api.pumpHost());

        FakeWingmanVoidApi.playerIds = new int[]{1, 2};
        assertEquals(List.of("GuestOne", "GuestTwo"), api.playerNames());

        api.reserveShip("GuestOne", "member-42");
        assertEquals(1, FakeWingmanVoidApi.reserved.size());
        assertEquals("GuestOne", FakeWingmanVoidApi.reserved.get(0)[0]);
        assertEquals("member-42", FakeWingmanVoidApi.reserved.get(0)[1]);
    }

    @Test
    void openHostReturnsFalseOnThrowOrMissingMethod() {
        FakeWingmanVoidApi.throwOnOpen = true;
        ReflectiveCoopWingmanApi throwing = new ReflectiveCoopWingmanApi(FakeWingmanVoidApi.class, () -> true);
        assertFalse(throwing.openHost());

        ReflectiveCoopWingmanApi missing = new ReflectiveCoopWingmanApi(EmptyApi.class, () -> true);
        assertFalse(missing.openHost());
        assertFalse(missing.isJoined());
        assertEquals("", missing.localName());
        assertEquals("", missing.pumpHost());
        assertEquals(List.of(), missing.playerNames());
    }

    @Test
    void supportsBooleanOpenHostOverload() {
        ReflectiveCoopWingmanApi api = new ReflectiveCoopWingmanApi(FakeWingmanBooleanApi.class, () -> true);
        assertTrue(api.openHost());
        FakeWingmanBooleanApi.openResult = false;
        assertFalse(api.openHost());
    }

    @Test
    void disabledModShortCircuitsBeforeResolvingApiClass() {
        ReflectiveCoopWingmanApi api = new ReflectiveCoopWingmanApi(FakeWingmanVoidApi.class, () -> false);
        assertFalse(api.wingmanEnabled());
        assertFalse(api.openHost());
        assertEquals(0, FakeWingmanVoidApi.openCalls);
    }
}
