package coop.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console is inert unless the headless launcher asks for it: the graphical client ships the same
 * class and must never start a stdin reader or a signal handler.
 */
class CoopHeadlessConsoleTest {

    @Test
    void disabledWithoutTheSystemProperty() {
        assertFalse(CoopHeadlessConsole.enabled());
    }

    @Test
    void statusLineWorksBeforeACampaignExists() {
        String line = CoopHeadlessConsole.statusLine(null);
        assertTrue(line.startsWith("[coop] status:"), line);
        assertTrue(line.contains("title/no-pump"), line);
    }
}
