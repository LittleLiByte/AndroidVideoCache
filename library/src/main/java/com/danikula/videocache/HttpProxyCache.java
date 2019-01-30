package com.danikula.videocache;

import android.text.TextUtils;
import android.util.Log;

import com.danikula.videocache.file.FileCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Locale;

import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;

/**
 * {@link ProxyCache} that read http url and writes data to {@link Socket}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class HttpProxyCache extends ProxyCache {
    public static final String TAG = HttpProxyCache.class.getSimpleName();

    //如果seek只是超出少许（这里设置为2M）仍然走缓存
    private static final int MINI_OFFSET_CACHE= 1024 * 1024 * 2;

    private final HttpUrlSource source;
    private final FileCache cache;
    private CacheListener listener;
    private boolean isForceCancel = false;

    public HttpProxyCache(HttpUrlSource source, FileCache cache) {
        super(source, cache);
        this.cache = cache;
        this.source = source;
    }

    public void registerCacheListener(CacheListener cacheListener) {
        this.listener = cacheListener;
    }

    public void processRequest(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String responseHeaders = newResponseHeaders(request);
        out.write(responseHeaders.getBytes("UTF-8"));

        long offset = request.rangeOffset;

        if (!isForceCancel && isUseCache(request)) {
            Log.i(TAG, "processRequest: responseWithCache");
            pauseCache(false);
            responseWithCache(out, offset);
        } else {
            Log.i(TAG, "processRequest: responseWithoutCache");
            pauseCache(true);
            responseWithoutCache(out, offset);
        }
    }

    /**
     * 是否强制取消缓存
     */
    public void cancelCache() {
        isForceCancel = true;
    }

    private boolean isUseCache(GetRequest request) throws ProxyCacheException {
        long sourceLength = source.length();
        boolean sourceLengthKnown = sourceLength > 0;
        long cacheAvailable = cache.available();
        // do not use cache for partial requests which too far from available cache. It seems user seek video.
        long offset = request.rangeOffset;
        //如果seek只是超出少许（这里设置为2M）仍然走缓存
        return !sourceLengthKnown || !request.partial || offset <= cacheAvailable + MINI_OFFSET_CACHE;
    }

    private String newResponseHeaders(GetRequest request) throws IOException, ProxyCacheException {
        String mime = source.getMime();
        boolean mimeKnown = !TextUtils.isEmpty(mime);
        long length = cache.isCompleted() ? cache.available() : source.length();
        boolean lengthKnown = length >= 0;
        long contentLength = request.partial ? length - request.rangeOffset : length;
        boolean addRange = lengthKnown && request.partial;
        return new StringBuilder()
                .append(request.partial ? "HTTP/1.1 206 PARTIAL CONTENT\n" : "HTTP/1.1 200 OK\n")
                .append("Accept-Ranges: bytes\n")
                .append(lengthKnown ? format("Content-Length: %d\n", contentLength) : "")
                .append(addRange ? format("Content-Range: bytes %d-%d/%d\n", request.rangeOffset, length - 1, length) : "")
                .append(mimeKnown ? format("Content-Type: %s\n", mime) : "")
                .append("\n") // headers end
                .toString();
    }

    private void responseWithCache(OutputStream out, long offset) throws ProxyCacheException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int readBytes;
        try {
            while ((readBytes = read(buffer, offset, buffer.length)) != -1 && !stopped) {
                out.write(buffer, 0, readBytes);
                offset += readBytes;
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void responseWithoutCache(OutputStream out, long offset) throws ProxyCacheException {
        HttpUrlSource newSourceNoCache = new HttpUrlSource(this.source);
        try {
            newSourceNoCache.open((int) offset);
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = newSourceNoCache.read(buffer)) != -1) {
                out.write(buffer, 0, readBytes);
                offset += readBytes;
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            newSourceNoCache.close();
        }
    }

    private String format(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }

    @Override
    protected void onCachePercentsAvailableChanged(int percents) {
        if (listener != null) {
            listener.onCacheAvailable(cache.file, source.getUrl(), percents);
        }
    }
}
