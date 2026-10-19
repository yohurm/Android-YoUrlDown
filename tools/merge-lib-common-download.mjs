#!/usr/bin/env node
/**
 * 把 GGEC-Android-EarBuds/lib_common 的下载子系统合入本仓库为 lib_download 模块。
 *
 * 只做**机械迁移**，不改逻辑：
 *   1. 复制 download/**（门面 + model + internal 全部）
 *   2. 复制 internal/util 里被下载用到的 FileNames / IoUtil
 *   3. 包名 com.yovo.lib_common.download.* -> com.yohu.lib_download.*
 *      com.yovo.lib_common.internal.util.* -> com.yohu.lib_download.util.*
 *      com.yovo.lib_common.log.YoLog      -> com.yohu.lib_download.log.YoLog
 *   4. 生成一个只含 d/i/w/e 的 YoLog 垫片（原 YoLog 依赖整个日志子系统，没必要搬）
 *
 * 用法：node tools/merge-lib-common-download.mjs <lib_common 源码根> <目标模块根>
 */
import { readdirSync, readFileSync, statSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';

const SRC = process.argv[2];
const DST = process.argv[3];
if (!SRC || !DST) {
  console.error('用法: node tools/merge-lib-common-download.mjs <src 源码根> <dst 模块根>');
  process.exit(2);
}

const SRC_PKG = join(SRC, 'com/yovo/lib_common');
const DST_JAVA = join(DST, 'src/main/java/com/yohu/lib_download');

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.java')) out.push(p);
  }
  return out;
}

function rewrite(text) {
  return text
    .replaceAll('com.yovo.lib_common.download', 'com.yohu.lib_download')
    .replaceAll('com.yovo.lib_common.internal.util', 'com.yohu.lib_download.util')
    .replaceAll('com.yovo.lib_common.log.YoLog', 'com.yohu.lib_download.log.YoLog');
}

function copyRewritten(srcFile, dstFile) {
  mkdirSync(dirname(dstFile), { recursive: true });
  writeFileSync(dstFile, rewrite(readFileSync(srcFile, 'utf8')), 'utf8');
}

let count = 0;

// 1) download/**  ->  com/yohu/lib_download/**
for (const f of walk(join(SRC_PKG, 'download'))) {
  const rel = f.substring(join(SRC_PKG, 'download').length + 1);
  copyRewritten(f, join(DST_JAVA, rel));
  count++;
}

// 2) internal/util 里被下载依赖的两个工具
for (const name of ['FileNames.java', 'IoUtil.java']) {
  const src = join(SRC_PKG, 'internal/util', name);
  if (existsSync(src)) {
    copyRewritten(src, join(DST_JAVA, 'util', name));
    count++;
  } else {
    console.warn(`未找到 ${name}，跳过`);
  }
}

// 3) YoLog 垫片（原实现依赖整个日志子系统；下载只用到 d/i/w/e）
const shim = `package com.yohu.lib_download.log;

/**
 * 下载子系统的日志垫片。
 *
 * <p>原 {@code lib_common} 的 {@code YoLog} 背后是一整套日志子系统（文件 sink、崩溃捕获、
 * 生命周期……），而下载子系统只用到 {@code d/i/w/e} 四个方法。为了让"合入下载"这件事
 * 不把整套日志一起拖进来，这里提供一个同签名的最小实现：默认静默，可挂一个 sink。
 */
public final class YoLog {

    /** 日志出口（可为 null，表示静默）。 */
    public interface Sink {
        void log(char level, String tag, String message);
    }

    private static volatile Sink sink;

    private YoLog() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void d(String tag, String message) {
        emit('d', tag, message);
    }

    public static void i(String tag, String message) {
        emit('i', tag, message);
    }

    public static void w(String tag, String message) {
        emit('w', tag, message);
    }

    public static void e(String tag, String message) {
        emit('e', tag, message);
    }

    public static void e(String tag, String message, Throwable t) {
        emit('e', tag, message + (t == null ? "" : " | " + t));
    }

    private static void emit(char level, String tag, String message) {
        Sink s = sink;
        if (s != null) {
            s.log(level, tag, message);
        }
    }
}
`;
mkdirSync(join(DST_JAVA, 'log'), { recursive: true });
writeFileSync(join(DST_JAVA, 'log/YoLog.java'), shim, 'utf8');
count++;

console.log(`已迁移 ${count} 个文件到 ${DST_JAVA}`);
