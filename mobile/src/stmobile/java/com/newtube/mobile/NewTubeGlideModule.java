package com.newtube.mobile;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.util.ContentLengthInputStream;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Enables annotation-discovered Glide library modules, including the animated-WebP decoder and HTTP/2 image loader. */
@GlideModule
public final class NewTubeGlideModule extends AppGlideModule {
    /**
     * Increased from 2 to 4 threads so scrolling feeds load thumbnails and channel avatars 2x faster in parallel
     * without blocking video startup.
     */
    private static final int SOURCE_THREADS = 4;

    /**
     * Glide's built-in 2500ms connect+read budget is a LAN default. On a high-RTT mobile link the
     * handshake alone can eat it, so thumbnails fail, the next bind re-requests them, and the result
     * is a request storm whose only visible outcome is permanently grey cards. Fail slowly instead.
     */
    private static final int HTTP_TIMEOUT_MS = 15_000;

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setSourceExecutor(GlideExecutor.newSourceBuilder()
                .setThreadCount(SOURCE_THREADS)
                .setName("newtube-glide-source")
                .build());

        builder.setDefaultRequestOptions(
                new RequestOptions().set(HttpGlideUrlLoader.TIMEOUT, HTTP_TIMEOUT_MS));

        // NEWTUBE(scroll): room for one more screen of decoded thumbnails.
        builder.setMemorySizeCalculator(new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(MEMORY_CACHE_SCREENS));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(32);
        dispatcher.setMaxRequestsPerHost(12);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectionPool(OkHttpManager.instance().getClient().connectionPool())
                .dispatcher(dispatcher)
                .fastFallback(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .build();

        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(okHttpClient));
    }

    private static final float MEMORY_CACHE_SCREENS = 3;

    /**
     * Lightweight self-contained HTTP/2 ModelLoader for GlideUrl.
     * Shares OkHttp connection pool and multiplexes requests to i.ytimg.com and yt3.ggpht.com.
     */
    private static final class OkHttpUrlLoader implements ModelLoader<GlideUrl, InputStream> {
        private final Call.Factory mClient;

        OkHttpUrlLoader(Call.Factory client) {
            mClient = client;
        }

        @Override
        public boolean handles(@NonNull GlideUrl url) {
            return true;
        }

        @Override
        public LoadData<InputStream> buildLoadData(@NonNull GlideUrl model, int width, int height, @NonNull Options options) {
            return new LoadData<>(model, new OkHttpStreamFetcher(mClient, model));
        }

        static final class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {
            private final Call.Factory mClient;

            Factory(Call.Factory client) {
                mClient = client;
            }

            @NonNull
            @Override
            public ModelLoader<GlideUrl, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
                return new OkHttpUrlLoader(mClient);
            }

            @Override
            public void teardown() {}
        }
    }

    private static final class OkHttpStreamFetcher implements DataFetcher<InputStream>, okhttp3.Callback {
        private final Call.Factory mClient;
        private final GlideUrl mUrl;
        private InputStream mStream;
        private ResponseBody mResponseBody;
        private DataCallback<? super InputStream> mCallback;
        private volatile Call mCall;

        OkHttpStreamFetcher(Call.Factory client, GlideUrl url) {
            mClient = client;
            mUrl = url;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
            Request.Builder requestBuilder = new Request.Builder().url(mUrl.toStringUrl());
            for (Map.Entry<String, String> headerEntry : mUrl.getHeaders().entrySet()) {
                requestBuilder.addHeader(headerEntry.getKey(), headerEntry.getValue());
            }
            Request request = requestBuilder.build();
            mCallback = callback;
            mCall = mClient.newCall(request);
            mCall.enqueue(this);
        }

        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
            if (mCallback != null) {
                mCallback.onLoadFailed(e);
            }
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) {
            mResponseBody = response.body();
            if (response.isSuccessful() && mResponseBody != null) {
                long contentLength = mResponseBody.contentLength();
                mStream = ContentLengthInputStream.obtain(mResponseBody.byteStream(), contentLength);
                if (mCallback != null) {
                    mCallback.onDataReady(mStream);
                }
            } else {
                if (mCallback != null) {
                    mCallback.onLoadFailed(new HttpException(response.message(), response.code()));
                }
            }
        }

        @Override
        public void cleanup() {
            try {
                if (mStream != null) {
                    mStream.close();
                }
            } catch (IOException ignored) {}
            if (mResponseBody != null) {
                mResponseBody.close();
            }
        }

        @Override
        public void cancel() {
            Call call = mCall;
            if (call != null) {
                call.cancel();
            }
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.REMOTE;
        }
    }
}
