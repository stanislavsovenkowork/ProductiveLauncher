package dev.stanislav.focusdeck.guard;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static dev.stanislav.focusdeck.guard.GuardController.*;
import static org.junit.Assert.*;

public class JournalCodecTest {
    private byte[] encode(Journal journal) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JournalCodec.write(journal, out);
        return out.toByteArray();
    }
    @Test public void snapshotAndEveryPhaseSurviveProcessRecreation() throws IOException {
        for (Phase phase : Phase.values()) {
            Journal before = new Journal(DEFAULT_POLICY, phase);
            assertEquals(before, JournalCodec.read(new ByteArrayInputStream(encode(before))));
        }
    }
    @Test public void everyTruncationIsRejectedRatherThanInventingARecoveryBaseline() throws IOException {
        byte[] bytes = encode(new Journal(DEFAULT_POLICY, Phase.PREPARED));
        for (int length = 0; length < bytes.length; length++) {
            byte[] partial = Arrays.copyOf(bytes, length);
            assertThrows(IOException.class, () -> JournalCodec.read(new ByteArrayInputStream(partial)));
        }
    }
    @Test public void unknownVersionsPhasesAndUnboundedCountsAreRejected() throws IOException {
        for (int position : new int[] {0, 4, 12}) {
            byte[] bytes = encode(new Journal(DEFAULT_POLICY, Phase.PREPARED));
            bytes[position] = 0x7f;
            assertThrows(IOException.class, () -> JournalCodec.read(new ByteArrayInputStream(bytes)));
        }
    }
    @Test public void trailingDataIsRejected() throws IOException {
        byte[] bytes = encode(new Journal(DEFAULT_POLICY, Phase.PREPARED));
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IOException.class, () -> JournalCodec.read(new ByteArrayInputStream(extra)));
    }
}
