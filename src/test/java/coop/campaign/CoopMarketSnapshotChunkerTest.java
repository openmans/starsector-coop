package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMarketSnapshotChunkerTest {

    @Test
    void smallPayloadsPassThroughUnchanged() {
        String small = "COMMODITY|supplies|500|100.0|";
        List<String> frames = CoopMarketSnapshotChunker.fragmentIfNeeded(1L, small);

        assertEquals(List.of(small), frames);
        assertFalse(CoopMarketSnapshotChunker.isChunk(frames.get(0)));

        CoopMarketSnapshotChunker.Assembler assembler = new CoopMarketSnapshotChunker.Assembler();
        assertEquals(small, assembler.accept("jangala", "storage", frames.get(0), 1_000L));
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void oversizedPayloadsFragmentAndReassembleAtomically() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("SHIP|hull_").append(i).append("|1|0.0|blob_data_").append(i).append('\n');
        }
        String big = sb.toString();

        List<String> parts = CoopMarketSnapshotChunker.fragmentIfNeeded(42L, big, 1024, 2048);
        assertTrue(parts.size() >= 3);
        for (String part : parts) {
            assertTrue(CoopMarketSnapshotChunker.isChunk(part));
        }

        CoopMarketSnapshotChunker.Assembler assembler = new CoopMarketSnapshotChunker.Assembler();
        for (int i = 0; i < parts.size() - 1; i++) {
            assertNull(assembler.accept("jangala", "storage", parts.get(i), 1_000L + i));
            assertEquals(1, assembler.pendingCount());
        }
        String assembled = assembler.accept("jangala", "storage", parts.get(parts.size() - 1), 2_000L);
        assertEquals(big, assembled);
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void tamperedChunkFailsShaVerificationAndDropsTransfer() {
        String payload = "A".repeat(5_000);
        List<String> parts = CoopMarketSnapshotChunker.fragmentIfNeeded(7L, payload, 1000, 1500);
        assertTrue(parts.size() >= 3);

        CoopMarketSnapshotChunker.Assembler assembler = new CoopMarketSnapshotChunker.Assembler();
        assertNull(assembler.accept("m1", "storage", parts.get(0), 100L));
        assertNull(assembler.accept("m1", "storage", parts.get(1), 101L));
        int lastColon = parts.get(parts.size() - 1).lastIndexOf(':');
        String lastPart = parts.get(parts.size() - 1);
        String corrupted = lastPart.substring(0, lastColon + 1)
                + lastPart.substring(lastColon + 1).replace('A', 'B');
        for (int i = 2; i < parts.size() - 1; i++) {
            assertNull(assembler.accept("m1", "storage", parts.get(i), 102L + i));
        }
        assertNull(assembler.accept("m1", "storage", corrupted, 200L));
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void stalePartialTransfersExpireAfterTimeout() {
        String payload = "X".repeat(5_000);
        List<String> parts = CoopMarketSnapshotChunker.fragmentIfNeeded(9L, payload, 1000, 1500);
        CoopMarketSnapshotChunker.Assembler assembler = new CoopMarketSnapshotChunker.Assembler();

        assertNull(assembler.accept("m1", "storage", parts.get(0), 1_000L));
        assertEquals(1, assembler.pendingCount());

        // Past timeout: triggers expiry and returns null for the second chunk alone.
        assertNull(assembler.accept("m1", "storage", parts.get(1),
                1_000L + CoopMarketSnapshotChunker.ASSEMBLY_TIMEOUT_MILLIS + 1L));
        assertEquals(1, assembler.pendingCount(), "fresh partial transfer started with part 1 only");
    }

    @Test
    void payloadPastMaxPartsSafetyCapIsRefused() {
        String tooBig = "Z".repeat(200);
        List<String> parts = CoopMarketSnapshotChunker.fragmentIfNeeded(
                1L, tooBig, 10, 2); // 100 parts > MAX_PARTS (32)
        assertTrue(parts.isEmpty());
    }
}
