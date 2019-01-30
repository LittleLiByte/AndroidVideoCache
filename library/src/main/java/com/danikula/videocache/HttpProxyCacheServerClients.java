package com.danikula.videocache;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.danikula.videocache.file.FileCache;

import java.io.File;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Client for {@link HttpProxyCacheServer}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
final class HttpProxyCacheServerClients {

    private final AtomicInteger clientsCount = new AtomicInteger(0);
    private final String url;
    private volatile HttpProxyCache proxyCache;
    private final List<CacheListener> listeners = new CopyOnWriteArrayList<>();
    private final CacheListener uiCacheListener;
    private final Config config;
    private boolean isCancelCache =false;
    private FileCache mCache;
    private String downloadPath=null;

    public HttpProxyCacheServerClients(String url, Config config) {
        this.url = checkNotNull(url);
        this.config = checkNotNull(config);
        this.uiCacheListener = new UiListenerHandler(url, listeners);
    }

    public HttpProxyCacheServerClients(String url, Config config,String downloadPath) {
        this(url,config);
        this.downloadPath=downloadPath;
    }

    public void processRequest(GetRequest request, Socket socket) {
        try {
            startProcessRequest();
            clientsCount.incrementAndGet();
            proxyCache.processRequest(request, socket);
        } catch (Exception e) {
            e.printStackTrace();
            if (e instanceof ProxyCacheException){
                uiCacheListener.onCacheError(e);
            }
        } finally {
            finishProcessRequest();
        }
    }

    protected void cancelCache(String url){
        isCancelCache =true;
    }

    private synchronized void startProcessRequest() throws ProxyCacheException {
        if (proxyCache == null){
            if (downloadPath==null){
                //原proxyCache
                proxyCache=newHttpProxyCache();
            }else{
                //本地已部分下载的视频文件作为缓存
                newHttpProxyCacheForDownloadFile(downloadPath);
            }
        }

        if (isCancelCache){
            proxyCache.cancelCache();
        }
    }

    private synchronized void finishProcessRequest() {
        if (clientsCount.decrementAndGet() <= 0) {
            proxyCache.shutdown();
            proxyCache = null;
        }
    }

    public void registerCacheListener(CacheListener cacheListener) {
        listeners.add(cacheListener);
    }

    public void unregisterCacheListener(CacheListener cacheListener) {
        listeners.remove(cacheListener);
    }

    public void shutdown() {
        listeners.clear();
        if (proxyCache != null) {
            proxyCache.registerCacheListener(null);
            proxyCache.shutdown();
            proxyCache = null;
        }
        clientsCount.set(0);
        //清除不必要的缓存
        if (mCache != null && isCancelCache && downloadPath == null) {
            mCache.file.delete();
        }
    }

    public int getClientsCount() {
        return clientsCount.get();
    }

    /**
     * 生成以已部分下载的视频为基础的缓存文件
     * @param downloadFilePath
     * @return
     * @throws ProxyCacheException
     */
    private void newHttpProxyCacheForDownloadFile(String downloadFilePath) throws ProxyCacheException {
        HttpUrlSource source = new HttpUrlSource(url, config.sourceInfoStorage, config.headerInjector);
        mCache = new FileCache(downloadFilePath);
        HttpProxyCache httpProxyCache = new HttpProxyCache(source, mCache);
        httpProxyCache.registerCacheListener(uiCacheListener);
        proxyCache = httpProxyCache;
    }

    private HttpProxyCache newHttpProxyCache() throws ProxyCacheException {
        HttpUrlSource source = new HttpUrlSource(url, config.sourceInfoStorage, config.headerInjector);
        mCache = new FileCache(config.generateCacheFile(url), config.diskUsage);
        HttpProxyCache httpProxyCache = new HttpProxyCache(source, mCache);
        httpProxyCache.registerCacheListener(uiCacheListener);
        return httpProxyCache;
    }

    private static final class UiListenerHandler extends Handler implements CacheListener {

        private final String url;
        private final List<CacheListener> listeners;

        public UiListenerHandler(String url, List<CacheListener> listeners) {
            super(Looper.getMainLooper());
            this.url = url;
            this.listeners = listeners;
        }

        @Override
        public void onCacheAvailable(File file, String url, int percentsAvailable) {
            Message message = obtainMessage();
            message.arg1 = percentsAvailable;
            message.obj = file;
            sendMessage(message);
        }

        @Override
        public void onCacheError(Throwable throwable) {
            for (CacheListener cacheListener : listeners) {
                cacheListener.onCacheError(throwable);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            for (CacheListener cacheListener : listeners) {
                cacheListener.onCacheAvailable((File) msg.obj, url, msg.arg1);
            }
        }
    }
}
