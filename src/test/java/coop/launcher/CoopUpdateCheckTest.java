package coop.launcher;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update check without a network. The fetcher is injected everywhere, so nothing in this file
 * can reach api.github.com even if it wanted to.
 */
class CoopUpdateCheckTest {

    /** Trimmed to the two fields the launcher reads, in GitHub's own shape. */
    private static final String RELEASE_BODY = """
            {
              "tag_name": "v0.3.0",
              "name": "0.3.0",
              "html_url": "https://github.com/AyoKeito/starsector-coop/releases/tag/v0.3.0",
              "draft": false,
              "prerelease": false
            }
            """;

    /** What GitHub answers for a repository that has never published a release. */
    private static final String NOT_FOUND_BODY = """
            {"message":"Not Found",\
            "documentation_url":"https://docs.github.com/rest/releases/releases#get-the-latest-release",\
            "status":"404"}
            """;

    // ---- the compare --------------------------------------------------------------------------

    @Test
    void versionsCompareNumericallyNotAsText() {
        assertTrue(CoopUpdateCheck.compare("0.10.0", "0.9.0") > 0,
                "10 is above 9 even though \"10\" sorts below \"9\"");
        assertTrue(CoopUpdateCheck.compare("1.0.0", "0.99.99") > 0);
        assertTrue(CoopUpdateCheck.compare("0.1.0", "0.1.1") < 0);
        assertEquals(0, CoopUpdateCheck.compare("0.1.0", "0.1.0"));
    }

    @Test
    void aMissingPartCountsAsZero() {
        assertEquals(0, CoopUpdateCheck.compare("1.2", "1.2.0"));
        assertEquals(0, CoopUpdateCheck.compare("1", "1.0.0"));
        assertTrue(CoopUpdateCheck.compare("1.2.1", "1.2") > 0);
    }

    @Test
    void aPartThatIsNotANumberCountsAsZeroRatherThanThrowing() {
        assertEquals(0, CoopUpdateCheck.compare("1.x.0", "1.0.0"));
        assertEquals(0, CoopUpdateCheck.compare("", ""));
        assertTrue(CoopUpdateCheck.compare("2.0.0", "nonsense") > 0);
    }

    @Test
    void aTagLosesItsVPrefixAndItsSuffix() {
        assertEquals("1.2.3", CoopUpdateCheck.normalise("v1.2.3"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("V1.2.3"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("  1.2.3  "));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("v1.2.3-rc1"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("1.2.3+build7"));
        assertEquals("", CoopUpdateCheck.normalise(null));
    }

    @Test
    void aPreReleaseOfTheVersionWeAreOnIsNotAnUpdate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.3.0", 200,
                RELEASE_BODY.replace("v0.3.0", "v0.3.0-rc2"));

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
    }

    // ---- the outcomes -------------------------------------------------------------------------

    @Test
    void aNewerReleaseIsAnUpdateWithALink() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UPDATE_AVAILABLE, outcome.kind());
        assertEquals("0.3.0", outcome.version());
        assertEquals("https://github.com/AyoKeito/starsector-coop/releases/tag/v0.3.0",
                outcome.url());

        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.WARN, row.status());
        assertEquals("Update available: 0.3.0", row.label());
        assertTrue(row.detail().contains("Both players must install the same release"), row.detail());
        assertTrue(row.detail().contains(outcome.url()), row.detail());
    }

    @Test
    void theSameVersionIsUpToDate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.3.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
        assertEquals("Up to date: 0.3.0", row.label());
    }

    @Test
    void aLocalBuildAheadOfTheReleaseIsUpToDate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.4.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
    }

    @Test
    void aRepositoryWithNoReleaseYetIsNeutralNotAWarning() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 404, NOT_FOUND_BODY);

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("no release published yet", outcome.reason());

        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.INFO, row.status());
        assertEquals("Update check: unavailable (no release published yet)", row.label());
        assertFalse(CoopInstallCheck.blocked(java.util.List.of(row)),
                "an update check must never block Launch");
    }

    @Test
    void aRateLimitIsNeutral() {
        assertEquals("GitHub rate limit", evaluate("0.1.0", 403, "{\"message\":\"rate limit\"}")
                .reason());
        assertEquals("GitHub rate limit", evaluate("0.1.0", 429, "").reason());
    }

    @Test
    void anyOtherStatusIsNeutralAndNamesTheCode() {
        assertEquals("HTTP 500", evaluate("0.1.0", 500, "<html>oops</html>").reason());
    }

    @Test
    void aBodyThatIsNotJsonIsNeutral() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, "<html>a captive portal</html>");

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("unreadable answer", outcome.reason());
    }

    @Test
    void aJsonBodyWithNoTagIsNeutral() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, "{\"name\":\"0.3.0\"}");

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("no tag_name in the answer", outcome.reason());
    }

    @Test
    void beingOfflineIsNeutralAndKeepsTheReason() {
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check("0.1.0", "https://example.invalid",
                (url, agent) -> {
                    throw new IOException("example.invalid");
                });

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("example.invalid", outcome.reason());
        assertEquals(CoopInstallCheck.Status.INFO, CoopUpdateCheck.row(outcome).status());
    }

    @Test
    void theUserAgentCarriesTheLauncherVersion() {
        String[] seen = new String[1];
        CoopUpdateCheck.check("0.1.0", CoopUpdateCheck.RELEASES_URL, (url, agent) -> {
            seen[0] = agent;
            return new CoopUpdateCheck.Response(200, RELEASE_BODY);
        });

        assertEquals("starsector-coop-launcher/0.1.0", seen[0]);
    }

    @Test
    void aVeryLongFailureMessageIsCutDownForTheRow() {
        String message = "x".repeat(500);
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check("0.1.0", "https://example.invalid",
                (url, agent) -> {
                    throw new IOException(message);
                });

        assertNotEquals(message, outcome.reason());
        assertTrue(outcome.reason().length() <= 80, outcome.reason());
    }

    // ---- multi-source GitHub fallbacks --------------------------------------------------------

    @Test
    void fallsBackToPrereleaseWhenLatestReturns404() {
        String listBody = """
                [
                  {
                    "tag_name": "v0.4.0",
                    "html_url": "https://github.com/openmans/starsector-coop/releases/tag/v0.4.0",
                    "draft": true,
                    "prerelease": true
                  },
                  {
                    "tag_name": "v0.3.1-rc1",
                    "html_url": "https://github.com/openmans/starsector-coop/releases/tag/v0.3.1-rc1",
                    "draft": false,
                    "prerelease": true
                  }
                ]
                """;
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.checkWithFallback("0.2.4", (url, agent) -> {
            if (CoopUpdateCheck.RELEASES_URL.equals(url)) {
                return new CoopUpdateCheck.Response(404, NOT_FOUND_BODY);
            }
            if (CoopUpdateCheck.RELEASES_LIST_URL.equals(url)) {
                return new CoopUpdateCheck.Response(200, listBody);
            }
            throw new AssertionError("unexpected URL: " + url);
        });

        assertEquals(CoopUpdateCheck.Kind.UPDATE_AVAILABLE, outcome.kind());
        assertEquals("0.3.1", outcome.version());
        assertEquals("https://github.com/openmans/starsector-coop/releases/tag/v0.3.1-rc1", outcome.url());
    }

    @Test
    void fallsBackToMasterVersionFileWhenNoReleasesExist() {
        String versionFile = """
                {
                    # Comment with # inside
                    "modName": "Starsector Coop V1",
                    "modVersion": {
                        "major": 0, # inline comment
                        "minor": 2,
                        "patch": 5
                    },
                    "directDownloadURL": "https://github.com/openmans/starsector-coop/releases/latest#top"
                }
                """;
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.checkWithFallback("0.2.4", (url, agent) -> {
            if (CoopUpdateCheck.RELEASES_URL.equals(url)) {
                return new CoopUpdateCheck.Response(404, NOT_FOUND_BODY);
            }
            if (CoopUpdateCheck.RELEASES_LIST_URL.equals(url)) {
                return new CoopUpdateCheck.Response(200, "[]");
            }
            if (CoopUpdateCheck.MASTER_VERSION_URL.equals(url)) {
                return new CoopUpdateCheck.Response(200, versionFile);
            }
            throw new AssertionError("unexpected URL: " + url);
        });

        assertEquals(CoopUpdateCheck.Kind.UPDATE_AVAILABLE, outcome.kind());
        assertEquals("0.2.5", outcome.version());
        assertEquals("https://github.com/openmans/starsector-coop/releases/latest#top", outcome.url());
    }

    @Test
    void fallsBackToMasterVersionFileOnGitHubApiRateLimit() {
        String versionFile = """
                {
                    "modVersion": { "major": 0, "minor": 2, "patch": 4 },
                    "directDownloadURL": "https://github.com/openmans/starsector-coop/releases/latest"
                }
                """;
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.checkWithFallback("0.2.4", (url, agent) -> {
            if (CoopUpdateCheck.RELEASES_URL.equals(url)) {
                return new CoopUpdateCheck.Response(403, "{\"message\":\"API rate limit exceeded\"}");
            }
            if (CoopUpdateCheck.MASTER_VERSION_URL.equals(url)) {
                return new CoopUpdateCheck.Response(200, versionFile);
            }
            throw new AssertionError("unexpected URL: " + url);
        });

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
        assertEquals("0.2.4", outcome.version());
        assertEquals("https://github.com/openmans/starsector-coop/releases/latest", outcome.url());
    }

    @Test
    void rowWithCurrentVersionShowsInstalledAndRemoteVersions() {
        CoopUpdateCheck.Outcome update = new CoopUpdateCheck.Outcome(
                CoopUpdateCheck.Kind.UPDATE_AVAILABLE, "0.2.5",
                "https://github.com/openmans/starsector-coop/releases/tag/v0.2.5", "");
        CoopInstallCheck.Row updateRow = CoopUpdateCheck.row(update, "0.2.4");
        assertEquals(CoopInstallCheck.Status.WARN, updateRow.status());
        assertTrue(updateRow.detail().contains("Installed 0.2.4"), updateRow.detail());
        assertTrue(updateRow.detail().contains("0.2.5"), updateRow.detail());

        CoopUpdateCheck.Outcome ahead = new CoopUpdateCheck.Outcome(
                CoopUpdateCheck.Kind.UP_TO_DATE, "0.2.4",
                "https://github.com/openmans/starsector-coop/releases/tag/v0.2.4", "");
        CoopInstallCheck.Row aheadRow = CoopUpdateCheck.row(ahead, "0.2.5");
        assertEquals(CoopInstallCheck.Status.OK, aheadRow.status());
        assertTrue(aheadRow.detail().contains("ahead of GitHub release 0.2.4"), aheadRow.detail());
    }

    private static CoopUpdateCheck.Outcome evaluate(String current, int status, String body) {
        return CoopUpdateCheck.check(current, CoopUpdateCheck.RELEASES_URL,
                (url, agent) -> new CoopUpdateCheck.Response(status, body));
    }
}
