package com.fongmi.android.tv.startup;

import java.util.List;

/**
 * Which startup steps are allowed to run before the first screen is drawn (T2-25).
 *
 * <p>The first screen must appear as fast as possible, so a startup step has to earn its place
 * on the critical path. Each step is classified explicitly, and the classification is testable
 * without a device.
 *
 * <p>Pure JVM on purpose: this decides what the app is allowed to do during launch, so it must
 * be unit-testable rather than buried in an Activity.
 */
public final class StartupBudget {

    /**
     * A startup step and whether it may block the first screen.
     *
     * @param id       stable identifier, used in the startup log
     * @param critical {@code true} when the first screen cannot be drawn without it
     */
    public record Step(String id, boolean critical) {}

    /**
     * The steps that must complete before the home screen can be shown.
     *
     * <p>Deliberately tiny. Everything else is deferred: none of it is visible on the first
     * screen, and each one costs main-thread time or network latency.
     */
    private static final List<Step> STEPS = List.of(
            new Step("store-restore", true),
            new Step("home-binding", true),
            new Step("config-load", false),
            new Step("config-file-read", false),
            new Step("live-config-load", false),
            new Step("wall-config-load", false),
            new Step("webview-create", false),
            new Step("native-site-warmup", false),
            new Step("jar-loader", false),
            new Step("device-discovery", false),
            new Step("background-services", false),
            new Step("dlnarenderer-service", false),
            new Step("permission-prompt", false));

    /** The delay before deferred work starts, giving the first frame room to draw. */
    public static final long DEFER_AFTER_FIRST_FRAME_MS = 80L;

    /**
     * The delay before the WebView is pre-created, counted from the first frame (T2-25).
     *
     * <p>Not zero on purpose: creating a WebView starts a renderer process, and doing it in the
     * same frame as the first draw is what used to make the first screen wait. The prewarm only
     * saves the cost of the first web home page, so it can afford to lose that race.
     */
    public static final long WEBVIEW_PREWARM_AFTER_FIRST_FRAME_MS = 150L;

    private StartupBudget() {}

    public static List<Step> steps() {
        return STEPS;
    }

    /**
     * Ids of the steps that are allowed to run before the first screen.
     *
     * <p>Used by the startup log so a device run can be checked against the intended budget
     * instead of trusting the code by eye.
     */
    public static List<String> criticalIds() {
        return STEPS.stream().filter(Step::critical).map(Step::id).toList();
    }

    /** Ids of the steps that must stay off the first-screen critical path. */
    public static List<String> deferredIds() {
        return STEPS.stream().filter(step -> !step.critical()).map(Step::id).toList();
    }

    public static boolean isCritical(String id) {
        for (Step step : STEPS) if (step.id().equals(id)) return step.critical();
        // An unknown step is never assumed to be safe to block on.
        return false;
    }

    /** Whether a step may run before the first screen is drawn. */
    public static boolean mayBlockFirstScreen(String id) {
        return isCritical(id);
    }

    /**
     * Whether the WebView may be created before the first screen.
     *
     * <p>Creating a WebView spins up the renderer process, which is one of the most expensive
     * single operations in the app. It is only needed when a site actually uses a web home page,
     * and even then not for the first drawn frame.
     */
    public static boolean mayCreateWebViewBeforeFirstScreen() {
        return false;
    }

    /** Delay between the first frame and the WebView prewarm, in milliseconds. */
    public static long webViewPrewarmDelayMs() {
        return WEBVIEW_PREWARM_AFTER_FIRST_FRAME_MS;
    }
}
