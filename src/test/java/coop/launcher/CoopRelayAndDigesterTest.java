package coop.launcher;

import coop.net.CoopRelayProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopRelayAndDigesterTest {

    @Test
    void modJarDigesterComputesDeterministicSha256PerJar(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("demo_mod").resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarFile = jarsDir.resolve("demo.jar");
        Files.writeString(jarFile, "fake-jar-bytes-v1", StandardCharsets.UTF_8);

        Map<String, String> digests = CoopModJarDigester.digestModsDirectory(tempDir);
        assertEquals(1, digests.size());
        String hash = digests.get("demo_mod/jars/demo.jar");
        assertTrue(hash != null && hash.length() == 64);

        String encoded = CoopModJarDigester.encodeProperty(digests);
        assertEquals("demo_mod/jars/demo.jar=" + hash, encoded);
    }

    @Test
    void relayServerPairsHostAndGuestSocketsAndBridgesTraffic() throws Exception {
        try (CoopRelayServer relay = new CoopRelayServer(0)) {
            relay.startAsync();
            int port = relay.boundPort();

            try (Socket host = new Socket("127.0.0.1", port);
                 Socket guest = new Socket("127.0.0.1", port)) {
                host.setSoTimeout(5_000);
                guest.setSoTimeout(5_000);

                OutputStream hostOut = host.getOutputStream();
                BufferedReader hostIn = new BufferedReader(
                        new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
                hostOut.write(CoopRelayProtocol.formatRegister("ALPHA1", "sec").getBytes(StandardCharsets.UTF_8));
                hostOut.flush();
                assertEquals(CoopRelayProtocol.RESP_OK, hostIn.readLine());

                OutputStream guestOut = guest.getOutputStream();
                BufferedReader guestIn = new BufferedReader(
                        new InputStreamReader(guest.getInputStream(), StandardCharsets.UTF_8));
                guestOut.write(CoopRelayProtocol.formatJoin("ALPHA1", "sec").getBytes(StandardCharsets.UTF_8));
                guestOut.flush();
                assertEquals(CoopRelayProtocol.RESP_OK, guestIn.readLine());

                // Bidirectional payload forwarding
                hostOut.write("HELLO-FROM-HOST\n".getBytes(StandardCharsets.UTF_8));
                hostOut.flush();
                assertEquals("HELLO-FROM-HOST", guestIn.readLine());

                guestOut.write("HELLO-FROM-GUEST\n".getBytes(StandardCharsets.UTF_8));
                guestOut.flush();
                assertEquals("HELLO-FROM-GUEST", hostIn.readLine());
            }
        }
    }
}
