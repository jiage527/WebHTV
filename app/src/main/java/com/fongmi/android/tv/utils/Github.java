package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

public class Github {

    /**
     * 二开项目自己的公开地址。
     *
     * 设置页「增强功能」里那个 GitHub 图标原来跳的是上游更新源仓库（BuildConfig.UPDATE_REPO_URL），
     * 点进去看到的是别人的主页。这里单独给一个常量：图标只负责「我的项目主页」，
     * 与更新源（UPDATE_GITHUB_REPO / UPDATE_CNB_BASE，决定版本检查从哪拉）互不影响。
     */
    public static final String PROJECT_URL = "https://github.com/jiage527/WebHTV";

    private static final String GITHUB_LATEST = "https://github.com/" + BuildConfig.UPDATE_GITHUB_REPO + "/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/" + BuildConfig.UPDATE_GITHUB_REPO + "/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/" + BuildConfig.UPDATE_GITHUB_REPO + "/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/" + BuildConfig.UPDATE_GITHUB_REPO + "/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/" + BuildConfig.UPDATE_GITHUB_REPO + "/releases/assets";
    private static final String CNB = BuildConfig.UPDATE_CNB_BASE;

    public static String getRepoUrl() {
        return BuildConfig.UPDATE_REPO_URL;
    }

    /** 设置页 GitHub 图标的目标地址（二开项目主页），用途见 {@link #PROJECT_URL}。 */
    public static String getProjectUrl() {
        return PROJECT_URL;
    }

    public static String getCnbAsset(String name) {
        return CNB + "/apk/" + name;
    }

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + tag + "/" + name;
    }

    public static String getJson(String name) {
        return getCnbAsset(name + ".json");
    }

    public static String getJson(String name, String channel) {
        if ("beta".equals(channel)) return getCnbAsset(name + "-beta.json");
        return getJson(name);
    }

    public static String getApk(String name) {
        return getCnbAsset(name + ".apk");
    }

    public static String getApk(String name, String channel) {
        if ("beta".equals(channel)) return getCnbAsset(name + "-beta.apk");
        return getApk(name);
    }

    public static String getAsset(String name, String channel) {
        return getCnbAsset(name);
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
    }

    public static String getReleasesApi() {
        return GITHUB_RELEASES_API + "?per_page=20";
    }

    public static String getLatestReleaseApi() {
        return GITHUB_RELEASES_API + "/latest";
    }

    public static String getReleaseAssetApi(long id) {
        return GITHUB_RELEASE_ASSETS_API + "/" + id;
    }
}
