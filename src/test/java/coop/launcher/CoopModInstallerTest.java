package coop.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for one-click GitHub archive download, extraction and install configuration. */
class CoopModInstallerTest {

    private static final String STOCK_VMPARAMS =
            "-Xms1536m -Xmx1536m -classpath janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;"
                    + "starfarer.api.jar;starfarer_obf.jar;jogg-0.0.7.jar;jorbis-0.0.15.jar;json.jar;"
                    + "jinput.jar;log4j-1.2.9.jar;lwjgl.jar;lwjgl_util.jar;fs.sound_obf.jar;"
                    + "fs.common_obf.jar;xstream-1.4.10.jar com.fs.starfarer.StarfarerLauncher";

    @Test
    void stripsLeadingCoopPrefixOnlyWhenPresent() {
        assertEquals("mod_info.json", CoopModInstaller.stripLeadingCoopPrefix("coop/mod_info.json"));
        assertEquals("jars/coop.jar", CoopModInstaller.stripLeadingCoopPrefix("COOP\\jars\\coop.jar"));
        assertEquals("mod_info.json", CoopModInstaller.stripLeadingCoopPrefix("mod_info.json"));
        assertEquals("", CoopModInstaller.stripLeadingCoopPrefix("coop/"));
        assertTrue(CoopModInstaller.isStageableJar("jars/coop.jar"));
        assertTrue(CoopModInstaller.isStageableJar("jars/coop-launcher.jar"));
        assertTrue(CoopModInstaller.isStageableJar("jars/flatlaf.jar"));
        assertFalse(CoopModInstaller.isStageableJar("mod_info.json"));
    }

    @Test
    void extractsCoopPrefixedArchiveIntoModRoot(@TempDir Path temp) throws IOException {
        Path zip = temp.resolve("coop-0.2.5.zip");
        writeZip(zip, Map.of(
                "coop/mod_info.json", "{\"id\":\"coop\",\"version\":\"0.2.5\"}",
                "coop/jars/coop.jar", "jar-bytes",
                "coop/jars/coop-forks.jar", "forks-bytes",
                "coop/data/config/settings.json", "{}"));

        Path modRoot = temp.resolve("install").resolve("mods").resolve("coop");
        CoopModInstaller.ExtractOutcome out = CoopModInstaller.extractArchive(zip, modRoot);

        assertEquals(4, out.filesExtracted());
        assertTrue(out.pendingFiles().isEmpty());
        assertTrue(Files.isRegularFile(modRoot.resolve("mod_info.json")));
        assertTrue(Files.isRegularFile(modRoot.resolve("jars/coop.jar")));
        assertTrue(Files.isRegularFile(modRoot.resolve("jars/coop-forks.jar")));
        assertTrue(Files.isRegularFile(modRoot.resolve("data/config/settings.json")));
    }

    @Test
    void rejectsZipSlipOutsideModRoot(@TempDir Path temp) throws IOException {
        Path zip = temp.resolve("evil.zip");
        writeZip(zip, Map.of("coop/../../escape.txt", "bad"));

        Path modRoot = temp.resolve("install").resolve("mods").resolve("coop");
        IOException ex = assertThrows(IOException.class,
                () -> CoopModInstaller.extractArchive(zip, modRoot));
        assertTrue(ex.getMessage().contains("unsafe zip entry"), ex.getMessage());
        assertFalse(Files.exists(temp.resolve("install").resolve("escape.txt")));
    }

    @Test
    void installLatestEndToEndExtractsPatchesVmparamsAndEnablesMod(@TempDir Path temp) throws IOException {
        Path root = createFakeStarsectorInstall(temp, STOCK_VMPARAMS);
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root.toFile());
        assertFalse(layout.isModInstalled());

        Path fixtureZip = temp.resolve("fixture.zip");
        writeZip(fixtureZip, Map.of(
                "coop/mod_info.json", "{\"id\":\"coop\",\"version\":\"0.2.5\",\"gameVersion\":\"0.98.5a-RC9\"}",
                "coop/jars/coop.jar", "coop-jar",
                "coop/jars/coop-forks.jar", "forks-jar",
                "coop/jars/coop-launcher.jar", "launcher-jar"));

        List<String> stages = new ArrayList<>();
        CoopModInstaller.InstallResult result = CoopModInstaller.installLatest(
                layout,
                "0.2.4",
                ver -> new CoopUpdateCheck.ReleaseAsset(
                        "0.2.5",
                        "https://github.com/openmans/starsector-coop/releases/tag/v0.2.5",
                        "https://github.com/openmans/starsector-coop/releases/download/v0.2.5/coop-0.2.5.zip",
                        "coop-0.2.5.zip"),
                (url, agent, target, progress) -> {
                    progress.onProgress("Downloading... 50%", 50);
                    Files.copy(fixtureZip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                },
                (stage, pct) -> stages.add(stage));

        assertTrue(result.ok(), result.summary());
        assertFalse(result.accessDenied());
        assertEquals("0.2.5", result.version());
        assertEquals(4, result.filesExtracted());
        assertTrue(layout.isModInstalled(), "core mod files should now exist");
        assertTrue(Files.isRegularFile(root.resolve("vmparams.backup")), "vmparams backup must be created");

        String patchedVm = Files.readString(root.resolve("vmparams"), StandardCharsets.ISO_8859_1);
        assertTrue(patchedVm.contains("-classpath " + CoopVmparamsText.FORKS_ENTRY), patchedVm);
        assertTrue(CoopVmparamsText.classpathSanity(patchedVm).ok(), patchedVm);

        String enabled = Files.readString(root.resolve("mods/enabled_mods.json"), StandardCharsets.UTF_8);
        assertTrue(enabled.contains("\"coop\""), enabled);
        assertFalse(stages.isEmpty());
    }

    @Test
    void resolveLatestAssetFindsCoopZipAndFallsBackToPrerelease() throws IOException {
        String prereleaseList = """
                [
                  {
                    "tag_name": "v0.3.0-rc1",
                    "html_url": "https://github.com/openmans/starsector-coop/releases/tag/v0.3.0-rc1",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "checksums.txt",
                        "browser_download_url": "https://example.invalid/checksums.txt"
                      },
                      {
                        "name": "coop-0.3.0.zip",
                        "browser_download_url": "https://github.com/openmans/starsector-coop/releases/download/v0.3.0-rc1/coop-0.3.0.zip"
                      }
                    ]
                  }
                ]
                """;
        CoopUpdateCheck.ReleaseAsset asset = CoopUpdateCheck.resolveLatestAsset("0.2.4", (url, agent) -> {
            if (CoopUpdateCheck.RELEASES_URL.equals(url)) {
                return new CoopUpdateCheck.Response(404, "{\"message\":\"Not Found\"}");
            }
            if (CoopUpdateCheck.RELEASES_LIST_URL.equals(url)) {
                return new CoopUpdateCheck.Response(200, prereleaseList);
            }
            throw new AssertionError("unexpected URL: " + url);
        });

        assertNotNull(asset);
        assertEquals("0.3.0", asset.version());
        assertEquals("coop-0.3.0.zip", asset.assetName());
        assertTrue(asset.zipUrl().endsWith("coop-0.3.0.zip"), asset.zipUrl());
    }

    @Test
    void resolveLatestWingmanAssetPicksWingmanZipNotPvpAddon() throws IOException {
        String body = """
                {
                  "tag_name": "v0.9.4-RC9",
                  "html_url": "https://github.com/NEOysod/TheWingmanProject/releases/tag/v0.9.4-RC9",
                  "assets": [
                    {
                      "name": "WingmanPvP-0.1.2-beta.zip",
                      "browser_download_url": "https://example.invalid/WingmanPvP-0.1.2-beta.zip"
                    },
                    {
                      "name": "Wingman-0.9.4-beta.zip",
                      "browser_download_url": "https://example.invalid/Wingman-0.9.4-beta.zip"
                    }
                  ]
                }
                """;
        CoopUpdateCheck.ReleaseAsset asset = CoopUpdateCheck.resolveLatestWingmanAsset("0.2.4", (url, agent) ->
                new CoopUpdateCheck.Response(200, body));

        assertNotNull(asset);
        assertEquals("0.9.4", asset.version());
        assertEquals("Wingman-0.9.4-beta.zip", asset.assetName());
        assertTrue(asset.zipUrl().endsWith("Wingman-0.9.4-beta.zip"), asset.zipUrl());
    }

    @Test
    void installLatestWithWingmanExtractsBothModsAndEnablesBothIds(@TempDir Path temp) throws IOException {
        Path root = createFakeStarsectorInstall(temp, STOCK_VMPARAMS);
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root.toFile());
        assertFalse(layout.isWingmanInstalled());

        Path coopZip = temp.resolve("coop.zip");
        writeZip(coopZip, Map.of(
                "coop/mod_info.json", "{\"id\":\"coop\",\"version\":\"0.2.5\"}",
                "coop/jars/coop.jar", "coop-jar",
                "coop/jars/coop-forks.jar", "forks-jar"));
        Path wingmanZip = temp.resolve("wingman.zip");
        writeZip(wingmanZip, Map.of(
                "Wingman/mod_info.json", "{\"id\":\"wingman_project\",\"version\":\"0.9.4-beta\"}",
                "Wingman/jars/Wingman.jar", "wm-jar"));

        CoopModInstaller.InstallResult result = CoopModInstaller.installLatest(
                layout,
                "0.2.4",
                true,
                ver -> new CoopUpdateCheck.ReleaseAsset("0.2.5", "https://example.invalid/coop",
                        "https://example.invalid/coop-0.2.5.zip", "coop-0.2.5.zip"),
                ver -> new CoopUpdateCheck.ReleaseAsset("0.9.4", "https://example.invalid/wm",
                        "https://example.invalid/Wingman-0.9.4-beta.zip", "Wingman-0.9.4-beta.zip"),
                (url, agent, target, progress) -> {
                    Path src = url.contains("Wingman") ? wingmanZip : coopZip;
                    Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                },
                null);

        assertTrue(result.ok(), result.summary());
        assertTrue(layout.isModInstalled());
        assertTrue(layout.isWingmanInstalled());
        assertTrue(result.summary().contains("Wingman"), result.summary());

        String enabled = Files.readString(root.resolve("mods/enabled_mods.json"), StandardCharsets.UTF_8);
        assertTrue(enabled.contains("\"coop\""), enabled);
        assertTrue(enabled.contains("\"wingman_project\""), enabled);
    }

    private static Path createFakeStarsectorInstall(Path temp, String vmparams) throws IOException {
        Path root = temp.resolve("Starsector");
        Files.createDirectories(root.resolve("starsector-core"));
        Files.createDirectories(root.resolve("jre/bin"));
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("starsector.exe"), "");
        Files.writeString(root.resolve("jre/bin/javaw.exe"), "");
        Files.writeString(root.resolve("vmparams"), vmparams, StandardCharsets.ISO_8859_1);
        return root;
    }

    private static void writeZip(Path zipPath, Map<String, String> entries) throws IOException {
        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Map.Entry<String, String> item : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(item.getKey()));
                zos.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}
