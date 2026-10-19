package com.yohu.lib_download.model;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载分块断点信息。
 */
public final class BlockInfo {

    private final int blockIndex;
    private final long startOffset;
    private final long endOffset;
    private long currentOffset;

    public BlockInfo(int blockIndex, long startOffset, long endOffset, long currentOffset) {
        this.blockIndex = blockIndex;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.currentOffset = currentOffset;
    }

    public int blockIndex() {
        return blockIndex;
    }

    public long startOffset() {
        return startOffset;
    }

    public long endOffset() {
        return endOffset;
    }

    public long currentOffset() {
        return currentOffset;
    }

    public void currentOffset(long currentOffset) {
        this.currentOffset = currentOffset;
    }

    public long downloadedBytes() {
        return Math.max(0L, currentOffset - startOffset);
    }

    public boolean isComplete() {
        return currentOffset > endOffset;
    }
}
