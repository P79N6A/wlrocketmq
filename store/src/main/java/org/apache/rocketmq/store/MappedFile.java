/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.util.LibC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.DirectBuffer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *  内存映射文件   封装MappedByteBuffer
 */
public class MappedFile extends ReferenceResource {

    protected static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    /**
     * 操作系统每页大小，默认 4k。
     */
    public static final int OS_PAGE_SIZE = 1024 * 4;
    /**
     * 当 前 JVM 实 例中 Mapped­File 虚拟内存 。
     */
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);
    /**
     * 当前 JVM 实例中 MappedFile 对象个数 。
     */
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);
    /**
     * 当前写入位置，下次开始写入的开始位置
     */
    protected final AtomicInteger wrotePosition = new AtomicInteger(0);
    /**
     * 当前文件的提交指针，如果开启 transientStore­PoolEnable，
     * 则数据会存储在 TransientStorePool 中， 然后提交到内存映射 ByteBuffer 中， 再 刷写到磁盘。
     */
    protected final AtomicInteger committedPosition = new AtomicInteger(0);
    /**
     * 当前flush位置，刷写到磁盘指针，该指针之前的数据持久化到磁盘中 。
     */
    private final AtomicInteger flushedPosition = new AtomicInteger(0);
    /**
     * 文件大小
     */
    protected int fileSize;
    /**
     * fileChannel
     * {@link #file}的channel = new RandomAccessFile(this.file, "rw").getChannel()
     *
     */
    protected FileChannel fileChannel;
    /**
     堆内存 ByteBuffer， 如果 不为空，数 据 首 先将存储在 该
     Buffer中， 然后提交到MappedFile对应的内存映射文件Buffer。 transientStorePoolEnable 为 true时不为空。
     * 写入缓冲
     */
    protected ByteBuffer writeBuffer = null;
    /**
     * writeBuffer缓存池（堆内存池， transientStor巳PoolEnable 为 true 时启用 ）
     */
    protected TransientStorePool transientStorePool = null;
    /**
     * 文件名
     */
    private String fileName;
    /**
     * 文件开始的offset。
     * 目前文件名即offset
     */
    private long fileFromOffset;
    /**
     * 文件
     */
    private File file;
    /**
     * 物理文件对应的内存映射 Buffer。
     */
    private MappedByteBuffer mappedByteBuffer;
    /**
     * 文件最后一次 内容写入时间 。
     */
    private volatile long storeTimestamp = 0;
    /**
     是否是 MappedFileQueue 队列中第一个文件
     */
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }

    public MappedFile(final String fileName, final int fileSize, final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }

    /**
     * 确保文件目录已存在
     *
     * @param dirName 目录名
     */
    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    public static void clean(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Method method = method(target, methodName, args);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";

        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                methodName = "attachment";
                break;
            }
        }

        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    public void init(final String fileName, final int fileSize, final TransientStorePool transientStorePool) throws IOException {
        //如果 transientstorePoolEnabIe 为 true，则初始化 MappedFile 的 writeBuffer， 从 transientStorePool
        init(fileName, fileSize);
        this.writeBuffer = transientStorePool.borrowBuffer();
        this.transientStorePool = transientStorePool;
    }

    /**
     * 初始化fileChannel、mappedByteBuffer
     * @param fileName 文件名
     * @param fileSize 文件大小
     * @throws IOException 文件不存在 or io异常
     */
    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        //初始化 fileFromOffset 为文件名，也就是文件名代表该文件的起始偏移量
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;

        ensureDirOK(this.file.getParent());

        try {
            //通过 RandomAccessFile创建读写文件通道
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            //并将文件内容使用 NIO 的内存映射 Buffer将文件映 射到内存中。
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            TOTAL_MAPPED_FILES.incrementAndGet();
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("create file channel " + this.fileName + " Failed. ", e);
            throw e;
        } catch (IOException e) {
            log.error("map file " + this.fileName + " Failed. ", e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    public long getLastModifiedTimestamp() {
        return this.file.lastModified();
    }

    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    /**
     * 附加消息到文件。
     * 实际是插入映射文件buffer
     *
     * @param msg 消息
     * @param cb 逻辑
     * @return 附加消息结果
     */
    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        assert msg != null;
        assert cb != null;

        int currentPos = this.wrotePosition.get();

        if (currentPos < this.fileSize) {
            //通过 slice()方法创建一个与 MappedFile 的共享 内 存区，并设置 position 为当前指针 。
            ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
            byteBuffer.position(currentPos);
            //todo 调用了AppendMessageCallback.doAppend()方法，而AppendMessageCallback是个接口，它的实现类DefaultAppendMessageCallback就在CommitLog类中，是个内部类。
            //DefaultAppendMessageCallback(commitlog的内部类)#doAppend 只是将消息追加在内存中，
            // 需要根据是同步刷盘还是异步刷盘方式，将内存中的数据持久化到磁盘，然后执行HA主从同步复制
            AppendMessageResult result =
                cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, msg);
            this.wrotePosition.addAndGet(result.getWroteBytes());
            this.storeTimestamp = result.getStoreTimestamp();
            return result;
        }

        //文件写满了
        log.error("MappedFile.appendMessage return null, wrotePosition: " + currentPos + " fileSize: "
            + this.fileSize);
        return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
    }

    /**
     *
     */
    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    /**
     * TODO 疑问：调用方是
     */
    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length);
            return true;
        }

        return false;
    }

    /**
     * flush
     *
     * @param flushLeastPages flush最小页数
     * @return The current flushed position
     */
    public int flush(final int flushLeastPages) {
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {
                int value = getReadPosition();

                try {
                    //直接调用mappedByteBuffer或fileChannel的force方法将内存中的数据持久化到磁盘
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }
                //指针设置
                this.flushedPosition.set(value);
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }

    /**
     * commit
     * 当{@link #writeBuffer}为null时，直接返回{@link #wrotePosition}
     *
     * @param commitLeastPages commit最小页数
     * @return 当前commit位置
     * 内存映射文件的提交动作由 MappedFile 的 commit方法实现
     */
    public int commit(final int commitLeastPages) {
        //表明 commit操作主体是 writeBuffer。
        if (writeBuffer == null) {
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        //执行提交操作， commitLeastPages 为本次提交最小的页数，如果待提交数据不满 commitLeastPages，
        // 则不执行本次提交操作，待下次提交 。
        if (this.isAbleToCommit(commitLeastPages)) {
            if (this.hold()) {
                commit0(commitLeastPages);
                this.release();
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // All dirty data has been committed to FileChannel. 写到文件尾时，回收writeBuffer。
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer);
            this.writeBuffer = null;
        }

        return this.committedPosition.get();
    }

    /**
     * commit实现，将writeBuffer写入fileChannel。
     * @param commitLeastPages commit最小页数。用不上该参数
     * commit的作用就是将MappedFile#­ writeBuffer 中的数据提交到文件通道 FileChannel 中。
     */
    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        if (writePos - this.committedPosition.get() > 0) {
            try {
                // 设置需要写入的byteBuffer
                //ByteBuffer使用技巧 : slice()方法创建一个共享缓存区，与原先的ByteBuffer共享内存但维护一套独立的指针(position、 mark、 limit)。
                ByteBuffer byteBuffer = writeBuffer.slice();
                //将新创建的 position 回 退到上一次提交的位置
                byteBuffer.position(lastCommittedPosition);
                //设置 limit为 wrotePosition (当前最大有效 数据指针)
                byteBuffer.limit(writePos);
                // 然后把commitedPosition到wrotePosition的数据复制 (写入)到FileChannel中
                this.fileChannel.position(lastCommittedPosition);
                this.fileChannel.write(byteBuffer);
                // 设置position.更新committedPosition指针为wrotePosition
                this.committedPosition.set(writePos);
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }

    /**
     * 是否能够flush。满足如下条件任意条件：
     * 1. 映射文件已经写满
     * 2. flushLeastPages > 0 && 未flush部分超过flushLeastPages
     * 3. flushLeastPages = 0 && 有新写入部分
     *
     * @param flushLeastPages flush最小分页
     * @return 是否能够写入
     */
    private boolean isAbleToFlush(final int flushLeastPages) {
        int flush = this.flushedPosition.get();
        int write = getReadPosition();

        if (this.isFull()) {
            return true;
        }

        if (flushLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= flushLeastPages;
        }

        return write > flush;
    }

    /**
     * 是否能够commit。满足如下条件任意条件：
     * 1. 映射文件已经写满
     * 2. commitLeastPages > 0 && 未commit部分超过commitLeastPages
     * 3. commitLeastPages = 0 && 有新写入部分
     *
     * @param commitLeastPages commit最小页数
     * @return 是否能够写入
     * 判断是否执行 commit操作。 如果文件己满返回 true ;
     * 如果 commitLeastPages大于 0, 则比较 wrotePosition( 当前 writeBuffe 的写指针)与上 一次提交的指针(committedPosition) 的差值，
     * 除以 OS_PAGE_SIZE得到当前脏页的数量，如果大于 commitLeastPages则返回 true;如果 commitLeastPages 小 于 0 表示只要存在脏页就提交 。
     */
    protected boolean isAbleToCommit(final int commitLeastPages) {
        int flush = this.committedPosition.get();
        int write = this.wrotePosition.get();

        if (this.isFull()) {
            return true;
        }

        if (commitLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= commitLeastPages;
        }

        return write > flush;
    }

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    /**
     * 根据 pos 获取 指定size 映射Buffer
     *
     * @see #getReadPosition()
     * @param pos 当前 Buffer 的 pos
     * @param size 长度
     * @return 映射Buffer
     */
    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {

            if (this.hold()) {
                //返回的共享缓存区空间为整个 Mapped­ File
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }

    /**
     * 根据 pos 获取 映射Buffer
     * 查找 pos 到当前最大可读之间的数据
     * @see #getReadPosition()
     * @param pos 当前 Buffer 的 pos
     * @return 映射Buffer
     */
    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {
                //返回的共享缓存区空间为整个 Mapped­ File
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                //待查找的值
                byteBuffer.position(pos);
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    @Override
    public boolean cleanup(final long currentRef) {
        //如果 available 为 true，表示 MappedFile 当前可用，无须清理
        if (this.isAvailable()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmaping.");
            return false;
        }

        //如果资 源已经被清除，返回 true
        if (this.isCleanupOver()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        //如果是堆外内存，调用堆外内存的 cleanup方法清除，
        // 维护 MappedFile类变量 TOTAL_MAPPED_VIRTUAL_MEMORY、 TOTAL_MAPPED_FILES并 返回 true，表示 cleanupOver为 true。
        clean(this.mappedByteBuffer);
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        TOTAL_MAPPED_FILES.decrementAndGet();
        log.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    //intervalForcibly 表示拒绝被销毁的最大存活时间
    public boolean destroy(final long intervalForcibly) {
        this.shutdown(intervalForcibly);

        if (this.isCleanupOver()) {
            try {
                //关闭文件通道， 删除物理文件。
                this.fileChannel.close();
                log.info("close file channel " + this.fileName + " OK");

                long beginTime = System.currentTimeMillis();
                boolean result = this.file.delete();
                log.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + UtilAll.computeEclipseTimeMilliseconds(beginTime));
            } catch (Exception e) {
                log.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            log.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * @return The max position which have valid data
     * RocketMQ 文件的 一 个组织方式是内存映射文件，预先申 请一块连续的固定大 小的 内存，
     * 需要一套指针标识当前最大有效数据 的位 置，获取最大有效数据偏移量的方法由 MappedFile 的 getReadPosition 方法实现
     *
     * 获取当前文件最大的可读指针 。 如果 writeBuffer 为 空， 则 直接 返回当前的 写指针;如 果 writeBuffer不为空，
     * 则返回上一次提交的指针。 在 MappedFile设计中，只有提交了的数 据(写入到 MappedByteBuffer或 FileChannel 中的数据 )才是安全的数据。
     */
    public int getReadPosition() {
        return this.writeBuffer == null ? this.wrotePosition.get() : this.committedPosition.get();
    }

    public void setCommittedPosition(int pos) {
        this.committedPosition.set(pos);
    }

    public void warmMappedFile(FlushDiskType type, int pages) {
        long beginTime = System.currentTimeMillis();
        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        int flush = 0;
        long time = System.currentTimeMillis();
        for (int i = 0, j = 0; i < this.fileSize; i += MappedFile.OS_PAGE_SIZE, j++) {
            byteBuffer.put(i, (byte) 0);
            // force flush when flush disk type is sync
            if (type == FlushDiskType.SYNC_FLUSH) {
                if ((i / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE) >= pages) {
                    flush = i;
                    mappedByteBuffer.force();
                }
            }

            // prevent gc
            if (j % 1000 == 0) {
                log.info("j={}, costTime={}", j, System.currentTimeMillis() - time);
                time = System.currentTimeMillis();
                try {
                    Thread.sleep(0);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        // force flush when prepare load finished
        if (type == FlushDiskType.SYNC_FLUSH) {
            log.info("mapped file warm-up done, force to disk, mappedFile={}, costTime={}",
                this.getFileName(), System.currentTimeMillis() - beginTime);
            mappedByteBuffer.force();
        }
        log.info("mapped file warm-up done. mappedFile={}, costTime={}", this.getFileName(),
            System.currentTimeMillis() - beginTime);

        this.mlock();
    }

    public String getFileName() {
        return fileName;
    }

    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }

    public void mlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        {
            int ret = LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize));
            log.info("mlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }

        {
            int ret = LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
            log.info("madvise {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }
    }

    public void munlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        int ret = LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize));
        log.info("munlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}
