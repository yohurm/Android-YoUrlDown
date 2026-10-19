#!/usr/bin/env node
/**
 * 移植规则层（11 组平台规则）与可独立运行的规则测试。
 *
 * 机械改写：
 *   com.dlparse.engine.rules        -> com.yohu.lib_urlparse.rules
 *   com.dlparse.engine.util.X       -> com.yohu.lib_urlparse.util.X
 *   com.dlparse.engine.(其它)        -> 若出现则报错（说明该文件还没被移植，需要先补依赖）
 *
 * 只移植"零 provider 依赖"的测试；依赖 provider 的测试等 B4/B5 完成后再移。
 *
 * 用法：node tools/port-rules-layer.mjs <旧 lib 根>
 */
import { readdirSync, readFileSync, writeFileSync, mkdirSync, copyFileSync, statSync, existsSync } from 'node:fs';
import { join, basename } from 'node:path';

const OLD_LIB = process.argv[2];
if (!OLD_LIB) {
  console.error('用法: node tools/port-rules-layer.mjs <旧 lib 根>');
  process.exit(2);
}

const OLD_MAIN = join(OLD_LIB, 'src/main/java/com/dlparse/engine');
const OLD_TEST = join(OLD_LIB, 'src/test/java/com/dlparse/engine');
const OLD_RES = join(OLD_LIB, 'src/test/resources');

const DST_MAIN = 'lib_urlparse/src/main/java/com/yohu/lib_urlparse';
const DST_TEST = 'lib_urlparse/src/test/java/com/yohu/lib_urlparse';
const DST_RES = 'lib_urlparse/src/test/resources';

function rewrite(text, where) {
  let out = text
    .replaceAll('com.dlparse.engine.rules', 'com.yohu.lib_urlparse.rules')
    .replaceAll('com.dlparse.engine.util', 'com.yohu.lib_urlparse.util');

  // 不该再出现旧工程的其它包（出现说明依赖还没移植）
  const leftover = out.match(/com\.dlparse\.[\w.]*/g);
  if (leftover) {
    throw new Error(`${where} 仍依赖未移植的包: ${[...new Set(leftover)].join(', ')}`);
  }
  return out;
}

function port(file, dstDir, pkgOverride) {
  let text = readFileSync(file, 'utf8');
  // 先改 package 行，否则残留检查会把 `package com.dlparse.engine;` 当成未移植依赖
  if (pkgOverride) {
    text = text.replace(/^package [\w.]+;/m, `package ${pkgOverride};`);
  }
  text = rewrite(text, basename(file));
  mkdirSync(dstDir, { recursive: true });
  writeFileSync(join(dstDir, basename(file)), text, 'utf8');
}

// 1) 11 组规则
const rulesDir = join(OLD_MAIN, 'rules');
let count = 0;
for (const f of readdirSync(rulesDir)) {
  if (!f.endsWith('.java')) continue;
  port(join(rulesDir, f), join(DST_MAIN, 'rules'));
  count++;
}

// 2) 纯规则测试（无 provider 依赖）
const TEST_FILES = ['ToutiaoWebRulesTest.java'];
for (const f of TEST_FILES) {
  const src = join(OLD_TEST, f);
  if (!existsSync(src)) {
    console.warn(`未找到测试 ${f}`);
    continue;
  }
  port(src, join(DST_TEST, 'rules'), 'com.yohu.lib_urlparse.rules');
  count++;
}

// 3) 固件
const FIXTURES = ['toutiao_mobile_page.html', 'toutiao_vod_response.json'];
mkdirSync(DST_RES, { recursive: true });
for (const f of FIXTURES) {
  const src = join(OLD_RES, f);
  if (existsSync(src)) {
    copyFileSync(src, join(DST_RES, f));
    count++;
  } else {
    console.warn(`未找到固件 ${f}`);
  }
}

console.log(`已移植 ${count} 个文件（规则 11 + 测试 + 固件 ${FIXTURES.length}）`);
