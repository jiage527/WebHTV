package com.fongmi.android.tv.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Pins the site warmup selection (T2-25).
 *
 * <p>The plan decides how much background work a launch does, so these tests keep it honest:
 * the home site must come first, a jar is only paid for once, and the number of warmed sites
 * stays small.
 */
public class SiteWarmupPlanTest {

    private static SiteWarmupPlan.Candidate site(String key, String jar, boolean homePage) {
        return new SiteWarmupPlan.Candidate(key, jar, homePage);
    }

    @Test
    public void homeSiteIsWarmedFirstEvenWhenItHasAWebHome() {
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("list", "list.jar", false),
                site("home", "home.jar", true)), "home");
        assertEquals(List.of("home", "list"), plan);
    }

    @Test
    public void oneSitePerJarBecauseTheJarLoadIsWhatCosts() {
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("a", "csp.jar", false),
                site("b", "csp.jar", false),
                site("c", "other.jar", false)), "", 10);
        assertEquals(List.of("a", "c"), plan);
    }

    @Test
    public void nativeListSitesComeBeforeWebHomeSites() {
        // A site without a web home needs its parser to render the first list; a web home site
        // does not, so the parser budget goes to the former.
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("web", "web.jar", true),
                site("list", "list.jar", false)), "", 10);
        assertEquals(List.of("list", "web"), plan);
    }

    @Test
    public void planIsCappedSoLaunchNeverWarmsTheWholeConfig() {
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("a", "a.jar", false),
                site("b", "b.jar", false),
                site("c", "c.jar", false)), "", 2);
        assertEquals(2, plan.size());
        assertEquals(SiteWarmupPlan.DEFAULT_MAX_WARMUPS, SiteWarmupPlan.planDefault(List.of(
                site("a", "a.jar", false),
                site("b", "b.jar", false),
                site("c", "c.jar", false)), "").size());
    }

    @Test
    public void sitesWithoutJarAreNotWarmupTargets() {
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("json", "", false),
                site("nullJar", null, false),
                site("jar", "csp.jar", false)), "", 10);
        assertEquals(List.of("jar"), plan);
    }

    @Test
    public void blankKeysAreIgnored() {
        List<String> plan = SiteWarmupPlan.planDefault(List.of(
                site("", "a.jar", false),
                site(null, "b.jar", false),
                site("ok", "c.jar", false)), "", 10);
        assertEquals(List.of("ok"), plan);
    }

    @Test
    public void customPlanKeepsTheUserOrder() {
        List<String> plan = SiteWarmupPlan.planCustom(List.of(
                site("second", "b.jar", true),
                site("first", "a.jar", false)));
        assertEquals(List.of("second", "first"), plan);
    }

    @Test
    public void emptyOrNullInputIsSafe() {
        assertTrue(SiteWarmupPlan.planDefault(List.of(), "home").isEmpty());
        assertTrue(SiteWarmupPlan.planDefault(null, "home").isEmpty());
        assertTrue(SiteWarmupPlan.planDefault(List.of(site("a", "a.jar", false)), "a", 0).isEmpty());
        assertTrue(SiteWarmupPlan.planCustom(null).isEmpty());
    }
}
