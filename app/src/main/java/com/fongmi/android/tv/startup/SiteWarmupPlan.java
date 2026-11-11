package com.fongmi.android.tv.startup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which sites get their spider warmed up in the background after the first screen (T2-25).
 *
 * <p>Warming a site means paying for its parser once, before the user taps it: the jar is loaded
 * and the spider is constructed. Two properties matter:
 *
 * <ul>
 *   <li><b>One site per jar.</b> The expensive part is the jar load, and it is shared by every
 *       site that points at the same jar, so warming a second site from the same jar buys nothing.
 *   <li><b>Home site first.</b> "Frequently used" cannot be derived from the database: {@code
 *       History} rows store the config id, not the site key, so there is no per-site usage
 *       counter. The home site is the one the user is looking at on the first screen, so it is
 *       the best available proxy, and it is followed by the sites whose parser is actually
 *       needed to render a list (no web home page) in configured order.
 * </ul>
 *
 * <p>Pure JVM on purpose: which sites get warmed is startup policy, so it has to be unit-testable
 * without a device.
 */
public final class SiteWarmupPlan {

    /**
     * A warmable site.
     *
     * @param key      site key, used to match the site again at runtime
     * @param jar      jar the site's spider comes from; blank when the site cannot be warmed
     * @param homePage whether the site renders its home as a web page (its parser is then not
     *                 needed for the first screen)
     */
    public record Candidate(String key, String jar, boolean homePage) {}

    /** Default number of sites warmed per config load. */
    public static final int DEFAULT_MAX_WARMUPS = 2;

    private SiteWarmupPlan() {}

    /** The default-mode plan: home site first, then native list sites, capped. */
    public static List<String> planDefault(List<Candidate> candidates, String homeKey) {
        return planDefault(candidates, homeKey, DEFAULT_MAX_WARMUPS);
    }

    public static List<String> planDefault(List<Candidate> candidates, String homeKey, int max) {
        if (candidates == null || candidates.isEmpty() || max <= 0) return List.of();
        List<Candidate> ordered = new ArrayList<>();
        if (homeKey != null && !homeKey.isEmpty()) {
            for (Candidate candidate : candidates) if (homeKey.equals(candidate.key())) ordered.add(candidate);
        }
        for (Candidate candidate : candidates) if (!candidate.homePage()) ordered.add(candidate);
        ordered.addAll(candidates);
        return onePerJar(ordered, max);
    }

    /** The custom-mode plan: exactly the user's order, one site per jar. */
    public static List<String> planCustom(List<Candidate> candidates) {
        return candidates == null ? List.of() : onePerJar(candidates, Integer.MAX_VALUE);
    }

    private static List<String> onePerJar(List<Candidate> ordered, int max) {
        List<String> result = new ArrayList<>();
        Set<String> jars = new LinkedHashSet<>();
        for (Candidate candidate : ordered) {
            if (result.size() >= max) break;
            if (candidate == null || candidate.key() == null || candidate.key().isEmpty()) continue;
            String jar = candidate.jar() == null ? "" : candidate.jar().trim();
            // No jar means no parser to pre-create, so it is not a warmup target.
            if (jar.isEmpty() || !jars.add(jar)) continue;
            if (!result.contains(candidate.key())) result.add(candidate.key());
        }
        return result;
    }
}
