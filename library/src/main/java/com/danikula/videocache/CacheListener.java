package com.danikula.videocache;

import java.io.File;

/**
 * Listener for cache availability.
 *
 * @author Egor Makovsky (yahor.makouski@gmail.com)
 * @author Alexey Danilov (danikula@gmail.com).
 */
public interface CacheListener {

    void onCacheAvailable(File cacheFile, String url, int percentsAvailable);

    /**
     * 缓存处理错误回调
     * @param throwable
     */
    void onCacheError(Throwable throwable);
}
