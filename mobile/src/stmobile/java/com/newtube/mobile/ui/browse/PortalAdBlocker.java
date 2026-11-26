package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * GUTUBE(portal-adblock): Multi-layer AdGuard DNS + OISD + HaGeZi + StevenBlack ad, popunder &
 * tracker blocker with instant offline seed + 24h background sync to local disk cache.
 */
public final class PortalAdBlocker {

    private static final String PREFS = "gutube_adblock";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String CACHE_FILE = "portal_adblock_hosts.txt";
    private static final long SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static final int FORMAT_HOSTS = 0;
    private static final int FORMAT_DOMAINS = 1;
    private static final int FORMAT_ADBLOCK = 2;

    private static final String SOURCE_STEVEN_BLACK =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String SOURCE_ADGUARD_DNS =
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt";
    private static final String SOURCE_OISD_BIG =
            "https://big.oisd.nl/domainswild2";
    private static final String SOURCE_HAGEZI_PRO =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt";

    private static final List<String> CORE_SEED_DOMAINS = Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "exoclick.com",
            "exosrv.com",
            "magsrv.com",
            "topcreativeformat.com",
            "highperformanceformat.com",
            "profitabledisplaynetwork.com",
            "adsco.re",
            "clksite.com",
            "onclickalgo.com",
            "plugrush.com",
            "ero-advertising.com",
            "adxpansion.com",
            "crackrevenue.com",
            "awempire.com",
            "chaturbate.com",
            "stripchat.com",
            "livejasmin.com",
            "bongacams.com",
            "camsoda.com",
            "imlive.com",
            "streamate.com",
            "xlovecam.com",
            "cam4.com",
            "adtng.com",
            "juicyads.com",
            "trafficjunky.net",
            "trafficfactory.biz",
            "adsterra.com",
            "hilltopads.net",
            "monetag.com",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "mgid.com",
            "revcontent.com",
            "clickadu.com",
            "tsyndicate.com",
            "realsrv.com",
            "syndication.realsrv.com",
            "onclkds.com",
            "acscdn.com",
            "bet365.com",
            "1xbet.com"
    );

    private static final String[] AD_PATH_SIGNATURES = {
            "/pagead/",
            "/pop/",
            "/popunder",
            "/pu.js",
            "/ads.js",
            "/adserver",
            "/vast",
            "/vast.xml",
            "/vmap",
            "/vmap.xml",
            "ad_type=",
            "zoneid=",
            "clickid=",
            "subid=",
            "bannerid=",
            "exoclick",
            "juicyads",
            "trafficjunky",
            "popads",
            "adsterra",
            "plugrush",
            "magsrv",
            "realsrv",
            "tsyndicate"
    };

    private static final String[] VIDEO_EXTENSIONS = {
            ".m3u8", ".mp4", ".ts", ".webm"
    };

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();

    private static volatile PortalAdBlocker sInstance;

    private final Context mAppContext;
    private final Set<String> mBlockedHosts = ConcurrentHashMap.newKeySet();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private PortalAdBlocker(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
        mBlockedHosts.addAll(CORE_SEED_DOMAINS);
        mExecutor.execute(this::initFromDiskAndSync);
    }

    @NonNull
    public static PortalAdBlocker getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (PortalAdBlocker.class) {
                if (sInstance == null) {
                    sInstance = new PortalAdBlocker(context);
                }
            }
        }
        return sInstance;
    }

    private void initFromDiskAndSync() {
        loadDiskCache();
        SharedPreferences sp = mAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastSync = sp.getLong(KEY_LAST_SYNC_MS, 0L);
        if (mBlockedHosts.size() < 500 || System.currentTimeMillis() - lastSync >= SYNC_INTERVAL_MS) {
            syncFromGitHub(sp);
        }
    }

    private void loadDiskCache() {
        File file = new File(mAppContext.getFilesDir(), CACHE_FILE);
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = line.trim();
                if (!domain.isEmpty()) {
                    mBlockedHosts.add(domain);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void syncFromGitHub(@NonNull SharedPreferences sp) {
        Set<String> merged = new HashSet<>(CORE_SEED_DOMAINS);
        boolean anySuccess = false;

        if (fetchHostsFile(SOURCE_ADGUARD_DNS, FORMAT_ADBLOCK, merged)) {
            anySuccess = true;
        }
        if (fetchHostsFile(SOURCE_OISD_BIG, FORMAT_DOMAINS, merged)) {
            anySuccess = true;
        }
        if (fetchHostsFile(SOURCE_HAGEZI_PRO, FORMAT_DOMAINS, merged)) {
            anySuccess = true;
        }
        if (fetchHostsFile(SOURCE_STEVEN_BLACK, FORMAT_HOSTS, merged)) {
            anySuccess = true;
        }

        if (!anySuccess) {
            return;
        }

        mBlockedHosts.addAll(merged);

        File target = new File(mAppContext.getFilesDir(), CACHE_FILE);
        File tmp = new File(mAppContext.getFilesDir(), CACHE_FILE + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (String domain : merged) {
                writer.write(domain);
                writer.newLine();
            }
            writer.flush();
        } catch (Throwable ignored) {
            return;
        }
        if (tmp.renameTo(target)) {
            sp.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
        }
    }

    private static boolean fetchHostsFile(@NonNull String url, int format, @NonNull Set<String> out) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return false;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String domain = parseDomainLine(line, format);
                    if (domain != null) {
                        out.add(domain);
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    static String parseDomainLine(@NonNull String rawLine, int format) {
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String domain;
        if (format == FORMAT_ADBLOCK) {
            if (trimmed.startsWith("!") || trimmed.startsWith("[") || trimmed.startsWith("@@")) {
                return null;
            }
            if (!trimmed.startsWith("||")) {
                return null;
            }
            int caret = trimmed.indexOf('^', 2);
            if (caret <= 2) {
                return null;
            }
            domain = trimmed.substring(2, caret);
            if (domain.contains("/") || domain.contains("*")) {
                return null;
            }
        } else {
            int commentIdx = trimmed.indexOf('#');
            String line = (commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed).trim();
            if (line.isEmpty()) {
                return null;
            }
            if (format == FORMAT_HOSTS) {
                if (line.startsWith("0.0.0.0 ")) {
                    domain = line.substring(8).trim();
                } else if (line.startsWith("127.0.0.1 ")) {
                    domain = line.substring(10).trim();
                } else {
                    return null;
                }
                int spaceIdx = domain.indexOf(' ');
                if (spaceIdx > 0) {
                    domain = domain.substring(0, spaceIdx);
                }
                int tabIdx = domain.indexOf('\t');
                if (tabIdx > 0) {
                    domain = domain.substring(0, tabIdx);
                }
            } else {
                domain = line;
            }
        }

        domain = domain.toLowerCase(Locale.US);
        if (domain.startsWith("*.")) {
            domain = domain.substring(2);
        }
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty()
                || "localhost".equals(domain)
                || "localhost.localdomain".equals(domain)
                || "0.0.0.0".equals(domain)
                || "127.0.0.1".equals(domain)
                || "broadcasthost".equals(domain)
                || !domain.contains(".")) {
            return null;
        }
        return domain;
    }

    public boolean isBlockedHost(@Nullable String rawHost) {
        if (TextUtils.isEmpty(rawHost)) {
            return false;
        }
        String host = rawHost.toLowerCase(Locale.US).trim();
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || "googlevideo.com".equals(host) || host.endsWith(".googlevideo.com")) {
            return false;
        }

        String candidate = host;
        while (!candidate.isEmpty()) {
            if (mBlockedHosts.contains(candidate)) {
                return true;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0 || dot + 1 >= candidate.length()) {
                break;
            }
            candidate = candidate.substring(dot + 1);
        }
        return false;
    }

    public boolean isBlockedRequest(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String host = uri.getHost();
        if (host != null) {
            String lowerHost = host.toLowerCase(Locale.US);
            if ("googlevideo.com".equals(lowerHost) || lowerHost.endsWith(".googlevideo.com")) {
                return false;
            }
        }

        String path = uri.getPath();
        if (path != null) {
            String lowerPath = path.toLowerCase(Locale.US);
            for (String ext : VIDEO_EXTENSIONS) {
                if (lowerPath.endsWith(ext)) {
                    return false;
                }
            }
        }

        if (isBlockedHost(host)) {
            return true;
        }

        String fullUrl = uri.toString().toLowerCase(Locale.US);
        for (String sig : AD_PATH_SIGNATURES) {
            if (fullUrl.contains(sig)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
}
