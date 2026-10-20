"""生成 app/src/main/res/drawable/ic_platform_<id>.xml。

品牌图形来自 simple-icons（CC0，/tmp/si/package/icons），没有收录的平台用
Noto Sans SC Bold 的单字字形。统一样式：24×24 圆角方块（品牌色）+ 白色图形。
"""
import os
import re
import sys

from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

SI = os.environ.get('SIMPLE_ICONS', '/tmp/si/package/icons')  # npm 包 simple-icons@16 的解压目录
OUT = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/res/drawable'
FONT = '/usr/share/fonts/google-noto-cjk/NotoSansSC-Bold.otf'
LINK = ("M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7"
        "c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1"
        "s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")

# id -> (颜色, ('si', 名称, 缩放) | ('glyph', 字) | ('path', d, 缩放))
SPEC = {
    'douyin':      ('#161823', ('si', 'tiktok', 0.6)),
    'wxSph':       ('#FA9D3B', ('glyph', '视')),
    'wxMp':        ('#07C160', ('si', 'wechat', 0.64)),
    'toutiao':     ('#F04142', ('glyph', '头')),
    'qutoutiao':   ('#FF7E00', ('glyph', '趣')),
    'ixigua':      ('#FE5C2B', ('glyph', '西')),
    'kuaishou':    ('#FF4906', ('si', 'kuaishou', 0.6)),
    'weishi':      ('#F8487A', ('glyph', '微')),
    'weibo':       ('#E6162D', ('si', 'sinaweibo', 0.66)),
    'bilibili':    ('#00A1D6', ('si', 'bilibili', 0.64)),
    'xiaohongshu': ('#FF2442', ('si', 'xiaohongshu', 0.86)),
    'pipixia':     ('#F2A20C', ('glyph', '皮')),
    'instagram':   ('#E4405F', ('si', 'instagram', 0.6)),
    'youtube':     ('#FF0000', ('si', 'youtube', 0.66)),
    'unknown':     ('#8E8E93', ('path', LINK, 0.66)),
}

BG = 'M6,0h12a6,6 0,0 1,6 6v12a6,6 0,0 1,-6 6h-12a6,6 0,0 1,-6 -6v-12a6,6 0,0 1,6 -6z'

font = TTFont(FONT)
gs = font.getGlyphSet()
cmap = font.getBestCmap()

def glyph_path(ch, box=13.0):
    name = cmap[ord(ch)]
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = box / max(x1 - x0, y1 - y0)
    w, h = (x1 - x0) * s, (y1 - y0) * s
    tx = (24 - w) / 2 - x0 * s
    ty = (24 - h) / 2 + y1 * s  # y 轴翻转
    pen = SVGPathPen(gs, ntos=lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
    gs[name].draw(TransformPen(pen, (s, 0, 0, -s, tx, ty)))
    return pen.getCommands()

def si_path(name):
    svg = open(os.path.join(SI, name + '.svg'), encoding='utf-8').read()
    return re.search(r' d="([^"]+)"', svg).group(1)

for pid, (color, art) in SPEC.items():
    if art[0] == 'glyph':
        body = f'    <path android:fillColor="#FFFFFF" android:pathData="{glyph_path(art[1])}" />\n'
        src = f'Noto Sans SC Bold「{art[1]}」'
    else:
        d = si_path(art[1]) if art[0] == 'si' else art[1]
        sc = art[2]
        off = round((24 - 24 * sc) / 2, 3)
        body = (f'    <group android:scaleX="{sc}" android:scaleY="{sc}"'
                f' android:translateX="{off}" android:translateY="{off}">\n'
                f'        <path android:fillColor="#FFFFFF" android:pathData="{d}" />\n'
                f'    </group>\n')
        src = f'simple-icons「{art[1]}」(CC0)' if art[0] == 'si' else 'Material “link”'
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           f'<!-- 平台标识：{pid}。白色图形来自 {src}，由 tools/icons/gen_platform_icons.py 生成。 -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n'
           f'    <path android:fillColor="{color}" android:pathData="{BG}" />\n'
           + body + '</vector>\n')
    open(os.path.join(OUT, f'ic_platform_{pid.lower()}.xml'), 'w', encoding='utf-8').write(xml)
    print(pid, len(xml))
