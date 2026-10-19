#!/usr/bin/env node
/**
 * 从旧工程移植"判据层"（sniff）与 cookie 罐到 lib_urlparse。
 *
 * 只做机械改写：包名 + import 前缀。旧工程的 sniff 包是自包含的（零外部依赖），
 * 因此可以整体搬过来，保留其中积累的判据（30 项黑名单、扩展名白名单、拦截页标记等）。
 *
 * 用法：node tools/port-sniff-layer.mjs <旧 engine 源码根>
 */
import { readdirSync, readFileSync, writeFileSync, mkdirSync, statSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';

const SRC = process.argv[2];
if (!SRC) {
  console.error('用法: node tools/port-sniff-layer.mjs <旧 engine 源码根>');
  process.exit(2);
}

const DST_JAVA = 'lib_urlparse/src/main/java/com/yohu/lib_urlparse';

const RULES = [
  ['com.dlparse.engine.sniffer', 'com.yohu.lib_urlparse.sniff'],
  ['com.dlparse.engine.http', 'com.yohu.lib_urlparse.fetch'],
];

function rewrite(text) {
  let out = text;
  for (const [from, to] of RULES) {
    out = out.replaceAll(from, to);
  }
  return out;
}

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.java')) out.push(p);
  }
  return out;
}

let n = 0;

// 1) sniff 整包
for (const f of walk(join(SRC, 'sniffer'))) {
  const dst = join(DST_JAVA, 'sniff', basename(f));
  mkdirSync(dirname(dst), { recursive: true });
  writeFileSync(dst, rewrite(readFileSync(f, 'utf8')), 'utf8');
  n++;
}

// 2) CookieJar（独立，无外部依赖）
const jar = join(SRC, 'http', 'CookieJar.java');
const jarDst = join(DST_JAVA, 'fetch', 'CookieJar.java');
mkdirSync(dirname(jarDst), { recursive: true });
writeFileSync(jarDst, rewrite(readFileSync(jar, 'utf8')), 'utf8');
n++;

console.log(`已移植 ${n} 个文件到 ${DST_JAVA}/{sniff,fetch}`);
