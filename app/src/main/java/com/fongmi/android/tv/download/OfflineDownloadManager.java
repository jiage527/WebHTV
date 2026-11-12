package com.fongmi.android.tv.download;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.OfflineDownloadService;
import com.fongmi.android.tv.ui.dialog.OfflineDownloadDialog;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 离线下载的运行时：下载队列、下载专用缓存、以及「播放时怎么读到下好的内容」。
 *
 * <p><b>为什么下载缓存要跟播放缓存分开。</b>播放缓存是 {@code <cacheDir>/exo} + LRU 驱逐器
 * （见 {@code MediaSourceFactory.getCache()}），系统空间紧张时它会自己删东西——用户刚下好的
 * 一整季被当成缓存垃圾清掉，是这类功能最典型的投诉。这里的下载缓存放在
 * {@code filesDir} 下（同样是私有目录，不需要任何存储权限），并且用
 * {@link NoOpCacheEvictor}：只由用户在面板里显式删除。
 *
 * <p><b>离线播放怎么实现。</b>Media3 官方做法是「下载与播放用同一个 {@link Cache}，并且
 * 两边的 cacheKey 一致」。所以下载时把 {@code customCacheKey} 设成下载 id，
 * 播放时（见 {@code ExoUtil.getMediaItem}）若地址命中一条已完成的下载，就把同一个 key
 * 塞回 {@code MediaItem}；列举 cacheKey 之后走这里的 {@link #dataSourceFactory()}，
 * 于是 CacheDataSource 直接命中本地分片，一字节都不用联网。HLS/DASH 下的是分片，
 * 靠的也是这套。
 *
 * <p><b>请求头。</b>Media3 的 {@code DownloadRequest} 不保存 headers，所以下载与离线播放
 * 都走 {@link ResolvingDataSource}，按 cacheKey 或「同源地址」回查
 * {@link OfflineDownloadRegistry} 里记下的请求头。少了这一层，需要 Referer 的直链会 403。
 */
public final class OfflineDownloadManager {

    private static final String TAG = "offline-download";
    private static final String DIR_NAME = "offline-downloads";
    private static final String REGISTRY_NAME = "offline-downloads.json";

    private static final class Loader {
        static final OfflineDownloadManager INSTANCE = new OfflineDownloadManager();
    }

    private final OfflineDownloadRegistry registry;
    private final Cache cache;
    private final StandaloneDatabaseProvider databaseProvider;
    private final DataSource.Factory dataSourceFactory;
    private final DownloadManager downloadManager;
    private volatile boolean started;

    private OfflineDownloadManager() {
        registry = new OfflineDownloadRegistry(Path.files(REGISTRY_NAME));
        databaseProvider = new StandaloneDatabaseProvider(App.get());
        cache = new SimpleCache(downloadDir(), new NoOpCacheEvictor(), databaseProvider);
        dataSourceFactory = buildDataSourceFactory();
        downloadManager = new DownloadManager(App.get(), databaseProvider, cache, dataSourceFactory, Task.executor());
        downloadManager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager manager, Download download, @Nullable Exception finalException) {
                // 下完/失败时把记录表刷一遍：面板靠它显示标题，播放靠它注入请求头。
                if (download.state == Download.STATE_COMPLETED || download.state == Download.STATE_FAILED) registry.save();
                if (download.state == Download.STATE_COMPLETED) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "completed id=%s bytes=%d", download.request.id, download.contentLength);
                } else if (download.state == Download.STATE_FAILED) {
                    SpiderDebug.log(TAG, "failed id=%s reason=%d", download.request.id, download.failureReason);
                }
            }
        });
    }

    public static OfflineDownloadManager get() {
        return Loader.INSTANCE;
    }

    /**
     * 下载存放位置：私有目录，不申请任何存储权限。
     */
    public static File downloadDir() {
        return Path.files(DIR_NAME);
    }

    public OfflineDownloadRegistry registry() {
        return registry;
    }

    public Cache cache() {
        return cache;
    }

    public DownloadManager downloadManager() {
        return downloadManager;
    }

    /**
     * 播放离线内容用的数据源：本地缓存优先，缺的部分回源补。
     */
    public DataSource.Factory dataSourceFactory() {
        return dataSourceFactory;
    }

    /**
     * 把一条下载请求交给 DownloadManager。
     *
     * <p>重复点同一集时按「地址 + 类型」去重：已经在队列里或已完成的直接返回 false，
     * 免得面板里冒出两条一模一样的条目。
     */
    public boolean enqueue(@NonNull OfflineDownloadRecord record) {
        if (!record.isValid()) return false;
        if (hasActive(record.url())) return false;
        registry.put(record);
        registry.save();
        SpiderDebug.log(TAG, "enqueue id=%s kind=%s url=%s", record.id(), record.kind(), record.url());
        DownloadRequest request = buildRequest(record);
        downloadManager.addDownload(request);
        startService();
        return true;
    }

    /**
     * 造下载请求。
     *
     * <p><b>customCacheKey 必须先问一句才能设。</b>Media3 对 HLS / SmoothStreaming 有硬性限制：
     * 这两类内容的 {@code customCacheKey} 必须为 null，否则 {@code DownloadRequest} 的构造直接抛
     * {@code IllegalArgumentException: customCacheKey must be null for type: 2}（规则见
     * {@link OfflineDownloadCacheKey}，已从字节码核对）。真机上「播放页点下载就闪退」就是这里
     * 无条件设 key 造成的——mp4 恰好不受限，所以只测 mp4 时看不出来。
     *
     * <p>不给 HLS 设 key 不影响离线播放：这类下载的缓存键本来就会退化成清单/分片自身的地址，
     * 播放侧的 {@link OfflineDownloadCache#keyFor} 会在 id 不命中时回退到按地址查。
     */
    private DownloadRequest buildRequest(OfflineDownloadRecord record) {
        Uri uri = Uri.parse(record.url());
        String mimeType = mimeTypeOf(record);
        int contentType = Util.inferContentTypeForUriAndMimeType(uri, mimeType);
        DownloadRequest.Builder builder = new DownloadRequest.Builder(record.id(), uri);
        if (!TextUtils.isEmpty(mimeType)) builder.setMimeType(mimeType);
        if (OfflineDownloadCacheKey.allows(contentType)) {
            builder.setCustomCacheKey(record.id());
        } else {
            SpiderDebug.log(TAG, "enqueue without cacheKey id=%s type=%d url=%s", record.id(), contentType, record.url());
        }
        return builder.build();
    }

    /**
     * Media3 自己要靠 mimeType 挑下载器（反射取 Hls/Dash/Progressive 的 Factory）。
     * 拿不准时返回 null，让它按地址与响应头自己推断。
     */
    @Nullable
    private static String mimeTypeOf(OfflineDownloadRecord record) {
        switch (record.kind()) {
            case HLS:
                return MimeTypes.APPLICATION_M3U8;
            case DASH:
                return MimeTypes.APPLICATION_MPD;
            default:
                return null;
        }
    }

    public void remove(String id) {
        if (TextUtils.isEmpty(id)) return;
        registry.remove(id);
        registry.save();
        downloadManager.removeDownload(id);
    }

    /**
     * 队列里是否已经有同一地址的条目（含已下完的）。面板据此把按钮显示成「已加入下载」。
     */
    public boolean hasActive(String url) {
        if (TextUtils.isEmpty(url)) return false;
        for (Download download : all()) {
            DownloadRequest request = download.request;
            if (request == null) continue;
            if (download.state == Download.STATE_REMOVING) continue;
            if (url.equals(request.uri.toString())) return true;
        }
        return false;
    }

    /**
     * 该地址是否已经离线可用（下完了）。播放器用它决定要不要走本地缓存。
     */
    @Nullable
    public String completedKeyFor(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return null;
        for (Download download : all()) {
            if (download.state != Download.STATE_COMPLETED) continue;
            DownloadRequest request = download.request;
            if (request != null && url.equals(request.uri.toString())) return request.customCacheKey;
        }
        return null;
    }

    /**
     * 这个 cacheKey 是不是我们下好的东西。播放侧据此切到下载缓存。
     */
    public boolean isOfflineKey(@Nullable String key) {
        if (TextUtils.isEmpty(key)) return false;
        for (Download download : all()) {
            DownloadRequest request = download.request;
            if (request == null || !key.equals(request.customCacheKey)) continue;
            if (download.state == Download.STATE_COMPLETED) return true;
        }
        return false;
    }

    @NonNull
    public List<Download> all() {
        try (DownloadCursor cursor = downloadManager.getDownloadIndex().getDownloads()) {
            List<Download> list = new ArrayList<>();
            while (cursor.moveToNext()) list.add(cursor.getDownload());
            return list;
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
            return Collections.emptyList();
        }
    }

    @Nullable
    public Download find(@Nullable String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (Download download : all()) if (download.request != null && id.equals(download.request.id)) return download;
        return null;
    }

    /**
     * 缓存目录实际占用。面板用来显示「已用 X」，也用来让用户判断该删哪一集。
     */
    public long usedBytes() {
        try {
            return cache.getCacheSpace();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public boolean paused() {
        try {
            return downloadManager.getDownloadsPaused();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 起前台服务。第一次调用会 init（DownloadService 必须在 onStartCommand 之前拿到
     * manager），由 service 内部的 getDownloadManager() 直接返回本单例，不再各自建一份。
     */
    public void startService() {
        if (started) return;
        started = true;
        try {
            OfflineDownloadService.start(App.get());
        } catch (Throwable e) {
            started = false;
            SpiderDebug.log(TAG, e);
        }
    }

    private DataSource.Factory buildDataSourceFactory() {
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(OkHttp.player());
        DataSource.Factory upstream = new DefaultDataSource.Factory(App.get(), httpFactory);
        // 下载器只拿得到 uri + cacheKey，请求头必须在这一层补回去。
        DataSource.Factory resolved = new ResolvingDataSource.Factory(upstream, dataSpec -> {
            Map<String, String> headers = registry.headersFor(dataSpec.uri.toString(), dataSpec.key);
            if (headers.isEmpty()) return dataSpec;
            return dataSpec.withRequestHeaders(headers);
        });
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(resolved)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /**
     * 下载面板/设置页用的入口，避免各处直接 new。
     */
    public static void openPanel(@Nullable androidx.fragment.app.FragmentActivity activity) {
        if (activity == null) return;
        OfflineDownloadDialog.show(activity);
    }
}
