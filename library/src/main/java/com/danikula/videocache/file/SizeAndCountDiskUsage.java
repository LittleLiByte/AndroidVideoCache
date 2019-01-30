package com.danikula.videocache.file;

import java.io.File;

/**
 * 类说明:自定义的缓存策略，同时限制缓存文件大小和缓存文件个数
 * Created by lizhj on 2019/1/8.
 */
public class SizeAndCountDiskUsage extends LruDiskUsage {
    private final int maxCount;
    private final long maxSize;


    public SizeAndCountDiskUsage(int maxCount, long maxSize) {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("Max count must be positive number!");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Max size must be positive number!");
        }
        this.maxCount = maxCount;
        this.maxSize = maxSize;
    }

    @Override
    protected boolean accept(File file, long totalSize, int totalCount) {
        return totalSize <= maxSize &&  totalCount<= maxCount;
    }
}
