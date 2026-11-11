package com.fongmi.android.tv.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the cold-start classification (T2-25).
 *
 * <p>These assertions exist so that a later change cannot silently move work back onto the
 * first-screen critical path: if someone marks a network or service step as critical, or starts
 * creating the WebView before the first frame, the test fails instead of the launch quietly
 * getting slower again.
 */
public class StartupBudgetTest {

    @Test
    public void firstScreenOnlyDependsOnRestoreAndBinding() {
        assertEquals(2, StartupBudget.steps().stream().filter(StartupBudget.Step::critical).count());
        assertTrue(StartupBudget.mayBlockFirstScreen("store-restore"));
        assertTrue(StartupBudget.mayBlockFirstScreen("home-binding"));
    }

    @Test
    public void networkAndServiceStepsNeverBlockTheFirstScreen() {
        String[] deferred = {
                "config-load",              // network config fetch
                "config-file-read",         // disk read
                "live-config-load",
                "wall-config-load",
                "webview-create",           // spins up the renderer process
                "native-site-warmup",
                "jar-loader",               // spider jar dex load
                "device-discovery",         // LAN multicast
                "background-services",
                "dlnarenderer-service",
                "permission-prompt",        // shows a dialog, cannot block a frame
        };
        for (String id : deferred) {
            assertFalse(id + " must not block the first screen",
                    StartupBudget.mayBlockFirstScreen(id));
        }
    }

    @Test
    public void unknownStepsAreNeverAssumedSafeToBlock() {
        assertFalse(StartupBudget.mayBlockFirstScreen("brand-new-step-nobody-classified"));
        assertFalse(StartupBudget.isCritical("brand-new-step-nobody-classified"));
        assertFalse(StartupBudget.mayBlockFirstScreen(null));
    }

    @Test
    public void webViewIsNeverCreatedBeforeTheFirstScreen() {
        // Creating a WebView is one of the most expensive single operations in the app and is
        // only needed when a site actually uses a web home page.
        assertFalse(StartupBudget.mayCreateWebViewBeforeFirstScreen());
    }

    @Test
    public void deferredWorkStartsAfterTheFirstFrameButSoonEnoughToHide() {
        // Long enough for the first frame to be drawn without competing for the main thread,
        // short enough that the user never notices a stutter.
        assertTrue(StartupBudget.DEFER_AFTER_FIRST_FRAME_MS > 0);
        assertTrue(StartupBudget.DEFER_AFTER_FIRST_FRAME_MS <= 200);
    }

    @Test
    public void stepIdsAreUniqueSoTheyStayUsableInTheStartupLog() {
        long distinct = StartupBudget.steps().stream().map(StartupBudget.Step::id).distinct().count();
        assertEquals(StartupBudget.steps().size(), distinct);
    }
}
