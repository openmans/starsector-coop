package coop.ui;

import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.net.CoopConnectionRole;
import coop.net.CoopInGameInvite;
import coop.net.CoopNetStartupConfig;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CoopInGameLauncherTest {

    @AfterEach
    void clearSeedProp() {
        System.clearProperty(CoopOptionsRegistry.NEW_GAME_SEED);
    }

    @Test
    void inviteFormatsAndRoundTripsIpv4Ipv6AndWorldParams() {
        String seed = CoopInGameInvite.generateSeed();
        assertNull(CoopNetStartupConfig.validateNewGameSeed(seed), "Generated seed must validate: " + seed);
        String pw = CoopInGameInvite.generatePassword();
        assertEquals(10, pw.length());

        String uri = CoopInGameInvite.format("203.0.113.9", 7777, seed, "p@ss w+1", "small", "young", "save_alpha");
        CoopInGameInvite.Parsed parsed = CoopInGameInvite.parse(uri);
        assertTrue(parsed.ok(), parsed.error());
        assertEquals("203.0.113.9", parsed.invite().host());
        assertEquals(7777, parsed.invite().port());
        assertEquals(seed, parsed.invite().seed());
        assertEquals("p@ss w+1", parsed.invite().password());
        assertEquals("small", parsed.invite().sectorSize());
        assertEquals("young", parsed.invite().sectorAge());
        assertEquals("save_alpha", parsed.invite().campaignId());

        String ipv6Uri = CoopInGameInvite.format("2001:db8::1", 8888, "", "", "", "", "");
        assertEquals("coop://[2001:db8::1]:8888/", ipv6Uri);
        CoopInGameInvite.Parsed parsedV6 = CoopInGameInvite.parse(ipv6Uri);
        assertTrue(parsedV6.ok(), parsedV6.error());
        assertEquals("2001:db8::1", parsedV6.invite().host());
        assertEquals(8888, parsedV6.invite().port());
    }

    @Test
    void inviteRejectsMalformedInputs() {
        assertFalse(CoopInGameInvite.parse("").ok());
        assertFalse(CoopInGameInvite.parse("http://1.2.3.4:7777").ok());
        assertFalse(CoopInGameInvite.parse("coop://1.2.3.4").ok());
        assertFalse(CoopInGameInvite.parse("coop://1.2.3.4:99999").ok());
    }

    @Test
    void sessionArmsHostGuestAndDisablesCleanlyThroughOptionsStore() {
        InMemoryJsonSource jsonSource = new InMemoryJsonSource();
        Properties props = new Properties();
        CoopOptionsStore store = new CoopOptionsStore(jsonSource, props::getProperty);

        CoopInGameLauncherSession session = new CoopInGameLauncherSession();
        session.setRussian(false);
        session.setHostAddress("192.168.1.50");
        session.setHostPort("7788");
        session.setHostPassword("secret99");
        String seed = CoopInGameInvite.generateSeed();
        session.setHostSeed(seed);
        session.cycleSectorSize(); // small

        assertTrue(session.applyHostConfig(store));
        assertEquals(CoopConnectionRole.HOST, session.armedRole());
        assertEquals("CO-OP · HOST :7788", session.titleButtonLabel());
        assertEquals(seed, System.getProperty(CoopOptionsRegistry.NEW_GAME_SEED));

        CoopNetStartupConfig hostCfg = CoopNetStartupConfig.from(store);
        assertTrue(hostCfg.isPresent());
        assertEquals(CoopConnectionRole.HOST, hostCfg.role());
        assertEquals(7788, hostCfg.port());
        assertEquals("secret99", hostCfg.password());
        assertEquals(seed, store.rawOneShot(CoopOptionsRegistry.NEW_GAME_SEED));
        assertEquals("small", store.rawOneShot(CoopOptionsRegistry.SECTOR_SIZE));

        // Now paste the host invite into a guest session
        String invite = session.buildHostInvite();
        assertTrue(invite.startsWith("coop://192.168.1.50:7788/?"));

        CoopInGameLauncherSession guest = new CoopInGameLauncherSession();
        guest.setRussian(true);
        assertTrue(guest.applyPastedInvite(invite));
        assertTrue(guest.applyGuestConfig(store));
        assertEquals(CoopConnectionRole.GUEST, guest.armedRole());
        assertEquals("CO-OP · ГОСТЬ", guest.titleButtonLabel());

        CoopNetStartupConfig guestCfg = CoopNetStartupConfig.from(store);
        assertTrue(guestCfg.isPresent());
        assertEquals(CoopConnectionRole.GUEST, guestCfg.role());
        assertEquals("192.168.1.50", guestCfg.host());
        assertEquals(7788, guestCfg.port());
        assertEquals("secret99", guestCfg.password());
        assertEquals(seed, store.rawOneShot(CoopOptionsRegistry.NEW_GAME_SEED));

        // Disabling restores singleplayer cleanly
        guest.disableCoop(store);
        assertEquals(CoopConnectionRole.NONE, guest.armedRole());
        assertEquals("CO-OP", guest.titleButtonLabel());
        assertFalse(CoopNetStartupConfig.from(store).isPresent());
        assertNull(System.getProperty(CoopOptionsRegistry.NEW_GAME_SEED));
    }

    private static final class InMemoryJsonSource implements CoopOptionsStore.JsonSource {
        private JSONObject common;

        @Override
        public JSONObject shipped() {
            return null;
        }

        @Override
        public JSONObject common() {
            return common;
        }

        @Override
        public boolean writeCommon(JSONObject json) {
            this.common = json;
            return true;
        }
    }
}
