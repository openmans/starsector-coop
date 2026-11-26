package coop.launcher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real Swing layout regressions for the minimal single-screen updater + live log + launch UI. */
class CoopLauncherLayoutTest {
    private CoopLauncherApp app;
    private JFrame frame;

    @BeforeEach
    void buildHiddenWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a desktop for native window layout");
        SwingUtilities.invokeAndWait(() -> {
            CoopTheme.install();
            app = new CoopLauncherApp();
            invoke("buildFrame");
            frame = field("frame", JFrame.class);
            invoke("updateLaunchGate");
            frame.addNotify();
            frame.validate();
        });
    }

    @AfterEach
    void dispose() throws Exception {
        if (app != null) {
            SwingUtilities.invokeAndWait(() -> invoke("shutdown"));
        }
    }

    @Test
    void minimalSingleScreenShowsLogUpdateLaunchAndOpenFolder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel title = (JLabel) find(frame, "launcherTitle");
            JLabel version = (JLabel) find(frame, "launcherVersion");
            assertNotNull(title);
            assertNotNull(version);
            assertEquals(bounds(title).y + title.getBaseline(title.getWidth(), title.getHeight()),
                    bounds(version).y + version.getBaseline(version.getWidth(), version.getHeight()),
                    "Version must share the title's text baseline");

            assertNotNull(find(frame, "embeddedLogPanel"), "Log window must be embedded directly in main frame");
            assertTrue(find(frame, "embeddedLogPanel").isVisible());
            Component openFolder = find(frame, "openFolderButton");
            assertNotNull(openFolder, "Open folder button must be visible");
            assertTrue(openFolder.getHeight() > 0 && openFolder.getWidth() > 0, "Open folder button must be laid out");
            assertNotNull(find(frame, "installPathField"), "Game path field must be visible");
            assertTrue(find(frame, "installPathField").getHeight() > 0, "Game path field must be laid out");
            assertNotNull(find(frame, "updateActionButton"), "Update button must be visible");
            assertNotNull(find(frame, "launchButton"), "Launch button must be visible");
            assertNotNull(find(frame, "installWingmanBox"), "Optional Wingman checkbox must be present");
            assertNull(find(frame, "hostSegment"), "Host tab must not clutter the minimal external launcher");
            assertNull(find(frame, "guestSegment"), "Join tab must not clutter the minimal external launcher");

            JButton launch = field("launchButton", JButton.class);
            assertNotNull(launch.getIcon());
            assertNotNull(field("updateActionButton", JButton.class).getIcon());
            assertNotNull(field("installSummary", JLabel.class).getIcon());

            Rectangle footer = find(frame, "launchFooter").getBounds();
            assertTrue(footer.y >= 0);
            assertTrue(footer.y + footer.height <= frame.getContentPane().getHeight());
        });
    }

    @Test
    void launchAndOpenFolderEnableOnceInstallLayoutIsSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(field("openFolderButton", JButton.class).isEnabled());
            assertEquals("Select game .exe", field("launchButton", JButton.class).getText());

            setField("layout", CoopInstallLayout.ofInstallRoot(new File("build/layout-test-install")));
            invoke("updateLaunchGate");
            assertTrue(field("openFolderButton", JButton.class).isEnabled());
            assertEquals("Launch", field("launchButton", JButton.class).getText());
            assertTrue(field("launchButton", JButton.class).isEnabled());
            assertTrue(field("updateActionButton", JButton.class).isEnabled());
        });
    }

    @Test
    void updateNotificationBannerAppearsWhenNewerReleaseArrives() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Component banner = find(frame, "updateBanner");
            JLabel bannerLabel = (JLabel) find(frame, "updateBannerLabel");
            assertNotNull(banner);
            assertNotNull(bannerLabel);
            assertFalse(banner.isVisible());

            app.applyUpdateOutcome(
                    new CoopUpdateCheck.Outcome(CoopUpdateCheck.Kind.UPDATE_AVAILABLE, "0.2.6",
                            "https://github.com/openmans/starsector-coop/releases/tag/v0.2.6", ""),
                    "0.2.5", false);
            assertTrue(banner.isVisible(), "Update banner must appear when an update is available");
            assertTrue(bannerLabel.getText().contains("0.2.6"), bannerLabel.getText());
            assertEquals("Update 0.2.6", field("updateChip", JButton.class).getText());

            app.applyUpdateOutcome(
                    new CoopUpdateCheck.Outcome(CoopUpdateCheck.Kind.UP_TO_DATE, "0.2.5",
                            "https://github.com/openmans/starsector-coop/releases/tag/v0.2.5", ""),
                    "0.2.5", false);
            assertFalse(banner.isVisible(), "Update banner must hide once up to date");
            assertEquals("Up to date", field("updateChip", JButton.class).getText());
        });
    }

    @Test
    void languageToggleSwitchesBetweenRuAndEnLive() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            setField("layout", CoopInstallLayout.ofInstallRoot(new File("build/layout-test-install")));
            invoke("updateLaunchGate");

            JButton langBtn = field("langToggle", JButton.class);
            assertEquals("RU", langBtn.getText());
            assertEquals("Launch", field("launchButton", JButton.class).getText());
            assertEquals("Open folder", field("openFolderButton", JButton.class).getText());

            app.setLanguage(CoopLauncherI18n.Lang.RU, false);
            assertEquals("EN", langBtn.getText());
            assertEquals("Запустить", field("launchButton", JButton.class).getText());
            assertEquals("Открыть папку", field("openFolderButton", JButton.class).getText());
            assertTrue(field("installWingmanBox", JCheckBox.class).getText().contains("Wingman"));

            app.setLanguage(CoopLauncherI18n.Lang.EN, false);
            assertEquals("RU", langBtn.getText());
            assertEquals("Launch", field("launchButton", JButton.class).getText());
            assertEquals("Open folder", field("openFolderButton", JButton.class).getText());
        });
    }

    private Rectangle bounds(Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), frame);
    }

    private void setField(String name, Object value) {
        try {
            Field field = CoopLauncherApp.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(app, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void invoke(String name) {
        try {
            Method method = CoopLauncherApp.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(app);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private <T> T field(String name, Class<T> type) {
        try {
            Field field = CoopLauncherApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                Component match = find(nested, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
