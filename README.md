
本工程 forked from **danikula/AndroidVideoCache**,版本**2.7.1**

## 前言
因为项目需要，在原[**ijkplayer**](https://github.com/bilibili/ijkplayer)播放器的基础上要加入缓存功能，在调研了一番发现目前比较好的方案就是本地代理方案，其中**danikula/AndroidVideoCache**最为出名。但是AndroidVideoCache上面挂了2k+的issues，并且上一次的更新更是在半年前了。所以为了结合项目实际以及目前已知的问题，针对**danikula/AndroidVideoCache**做了些定制化优化。

原 **danikula/AndroidVideoCache README** 看[这里](https://github.com/danikula/AndroidVideoCache/blob/master/README.md)
## 正题
下面会分几点说下自己的定制优化之处。

### 1.视频拖动超过已缓存部分则停止缓存线程下载
AndroidVideoCache会一直连接网络下载数据，直到把数据下载完全，并且拖动要超过当前已部分缓存的大于当前视频已缓存大小加上视频文件的20%，才会走不缓存分支，并且原来的缓存下载不会立即停止。这样就造成一个问题，当前用户如果网络环境不是足够好或者当前视频文件本身比较大时，拖动到没有缓存的地方需要比较久才会播放。针对这一点所以做了自己的优化。
 `sourceLength * NO_CACHE_BARRIER`用一个较小的常量值代替，并且用户拖动超过已缓存部分则停止缓存下载线程，使得带宽可以用于从拖动点开始播放，更快地加载出用户所需要的部分。
 主要改动ProxyCache以及HttpProxyCache两个文件
 

``` java
	//HttpProxyCache.java
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
	...
	
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
```
这里对==isUseCache #800023==方法进行了修改，在只超出缓存一点点（这里设置成2M）就会停止缓存，避免在线播放以及缓存下载两个线程同时抢占带宽，造成跳转后需要比较长时间才会加载播放成功。
==responseWithCache #801e00==方法中对while加入stopped标记位判断，当进入`responseWithoutCache`分支时则会调用父类中的  `pauseCache(true);`方法，将父类中stopped标记为true，停止从代理缓存中返回数据给播放器。具体可以查看`HttpProxyCache`和`ProxyCache`两个类。


### 2.脱离播放器实现缓存（离线缓存）
AndroidVideoCache是依赖于播放器的，所以针对这个局限进行了修改。离线缓存说白了就是提前下载，无论视频是否下载完成，都可以将这提前下载好的部分作为视频缓存使用。这里对于下载不在具体展开，下载功能如何实现自行寻找合适的库。下面对只下载了部分的视频如何加入到本地代理中进行说明（全部已经下载好的视频就不需要经过本地代理了）
这里假设已部分下载的视频文件后缀为 **.download**;

### 2.1 修改FileCache.java
添加一个可传入本地具体路径FileCache构造函数
``` java
	//FileCache.java
    public FileCache(String downloadFilePath) throws ProxyCacheException{
        try {
            this.diskUsage = new UnlimitedDiskUsage();
            this.file = new File(downloadFilePath);
            this.dataFile = new RandomAccessFile(this.file, "rw");
        } catch (IOException e) {
            throw new ProxyCacheException("Error using file " + file + " as disc cache", e);
        }
    }
```
加入了一种缓存文件格式，则判断是否缓存完成需要做相应的修改

``` java
    @Override
    public synchronized void complete() throws ProxyCacheException {
        if (isCompleted()) {
            return;
        }

        close();
        String fileName;
        if (file.getName().endsWith(DOWNLOAD_TEMP_POSTFIX)) {
            //临时下载文件
            fileName = file.getName().substring(0, file.getName().length() - DOWNLOAD_TEMP_POSTFIX.length());
        } else {
            fileName = file.getName().substring(0, file.getName().length() - TEMP_POSTFIX.length());
        }
        File completedFile = new File(file.getParentFile(), fileName);
        boolean renamed = file.renameTo(completedFile);
        if (!renamed) {
            throw new ProxyCacheException("Error renaming file " + file + " to " + completedFile + " for completion!");
        }
        file = completedFile;
        try {
            dataFile = new RandomAccessFile(file, "r");
            diskUsage.touch(file);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening " + file + " as disc cache", e);
        }
    }
	
	...
	
    private boolean isTempFile(File file) {
        return file.getName().endsWith(TEMP_POSTFIX) 
                || file.getName().endsWith(DOWNLOAD_TEMP_POSTFIX);
    }
```
### 2.2 修改HttpProxyCacheServerClients
添加一个可传入本地视频文件的HttpProxyCacheServerClients构造函数,大部分修改都有注释，所以不再作额外解释了。
``` java
    private FileCache mCache;
    private String downloadPath=null;

    public HttpProxyCacheServerClients(String url, Config config) {
        this.url = checkNotNull(url);
        this.config = checkNotNull(config);
        this.uiCacheListener = new UiListenerHandler(url, listeners);
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
	...
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
	......
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

```

对，就是这么简单，本地部分下载的视频文件就可以作为视频的缓存了，并且在播放视频的时候，视频可以继续缓存，将数据续写到本地部分下载的视频文件。

### 3.高码率缓存，低码率不缓存
这个是我们的项目需要，对高清以上的高码率视频才去缓存，低码率视频则直接在线播放。这部分需要借助播放器本身的能力。这里以IjkPlayer为例，在onPrepare方法中调用HttpProxyCacheServer暴露出来的`cancelCache(mVideoUrl）`，其实是将HttpProxyCache中isForceCancel属性置为true，在seekTo之后重新发起代理请求，这时isForceCancel=true,将不会走缓存分支，而是在线播放。具体过程看源代码。

``` kotlin
public void onPrepared(IMediaPlayer mp) {
	...
	if ( !isLocalVideo && bitrate < MINI_BITRATE_USE_CACHE
                && mCacheManager.getDownloadTempPath(mVideoUrl)==null) 
		{
            bufferPoint = -1;
            mOnBufferUpdateListener.update(this, -1);
            mCacheManager.cancelCache(mVideoUrl);
            //注意：seekTo会重新发起请求本地代理，cancelCache后将不会走缓存分支
            if (lastWatchPosition==-1){
                seekTo(1);
            }else {
                seekTo(lastWatchPosition);
            }
        }
        if (mPreparedListener != null) {
            mPreparedListener.onPrepared(this);
        }
	...
}
```

### 4.其余小修改
其余部分修改不多，也不重要，就不细说了。值得一提的是清除了slf4j依赖，所有日志部分均使用Andrdoid自带的Log来输入日志。