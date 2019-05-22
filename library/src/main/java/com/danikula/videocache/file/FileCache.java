package com.danikula.videocache.file;

import android.util.Log;

import com.danikula.videocache.Cache;
import com.danikula.videocache.ProxyCacheException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * {@link Cache} that uses file for storing data.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class FileCache implements Cache {

    private static final String TEMP_POSTFIX = ".download";
    private static final String DOWNLOAD_TEMP_POSTFIX = ".dltmp";


    private final DiskUsage diskUsage;
    public File file;
    private RandomAccessFile dataFile;

    public FileCache(File file) throws ProxyCacheException {
        this(file, new UnlimitedDiskUsage());
    }

    public FileCache(String downloadFilePath) throws ProxyCacheException{
        try {
            this.diskUsage = new UnlimitedDiskUsage();

            this.file = new File(downloadFilePath);
            if (!this.file.exists() && downloadFilePath.endsWith(DOWNLOAD_TEMP_POSTFIX)) {
                downloadFilePath = downloadFilePath.substring(0,
                        downloadFilePath.length() - DOWNLOAD_TEMP_POSTFIX.length());
                Log.i("FileCache", "downloadFilePath: " + downloadFilePath);
                this.file = new File(downloadFilePath);
            }

            this.dataFile = new RandomAccessFile(this.file, "rw");
        } catch (IOException e) {
            throw new ProxyCacheException("Error using file " + file + " as disc cache", e);
        }
    }

    public FileCache(File file, DiskUsage diskUsage) throws ProxyCacheException {
        try {
            if (diskUsage == null) {
                throw new NullPointerException();
            }
            this.diskUsage = diskUsage;
            File directory = file.getParentFile();
            Files.makeDir(directory);
            boolean completed = file.exists();
            this.file = completed ? file : new File(file.getParentFile(), file.getName() + TEMP_POSTFIX);
            this.dataFile = new RandomAccessFile(this.file, completed ? "r" : "rw");
        } catch (IOException e) {
            throw new ProxyCacheException("Error using file " + file + " as disc cache", e);
        }
    }

    @Override
    public synchronized long available() throws ProxyCacheException {
        try {
            return (int) dataFile.length();
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading length of file " + file, e);
//            e.printStackTrace();
        }
//        return 0;
    }

    @Override
    public synchronized int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        try {
            dataFile.seek(offset);
            return dataFile.read(buffer, 0, length);
        } catch (IOException e) {
            String format = "Error reading %d bytes with offset %d from file[%d bytes] to buffer[%d bytes]";
            throw new ProxyCacheException(String.format(format, length, offset, available(), buffer.length), e);
        }
    }

    @Override
    public synchronized void append(byte[] data, int length) throws ProxyCacheException {
        try {
            if (isCompleted()) {
                throw new ProxyCacheException("Error append cache: cache file " + file + " is completed!");
            }
            dataFile.seek(available());
            dataFile.write(data, 0, length);
        } catch (IOException e) {
            String format = "Error writing %d bytes to %s from buffer with size %d";
            throw new ProxyCacheException(String.format(format, length, dataFile, data.length), e);
        }
    }

    @Override
    public synchronized void close() throws ProxyCacheException {
        try {
            dataFile.close();
            diskUsage.touch(file);
        } catch (IOException e) {
            throw new ProxyCacheException("Error closing file " + file, e);
        }
    }

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

    @Override
    public synchronized boolean isCompleted() {
        return !isTempFile(file);
    }

    /**
     * Returns file to be used fo caching. It may as original file passed in constructor as some temp file for not completed cache.
     *
     * @return file for caching.
     */
    public File getFile() {
        return file;
    }

    private boolean isTempFile(File file) {
        return file.getName().endsWith(TEMP_POSTFIX)
                || file.getName().endsWith(DOWNLOAD_TEMP_POSTFIX);
    }

}
