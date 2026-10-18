"""Render the Coolapk campaign with local fonts and Pillow; no generated UI/photos."""
from pathlib import Path
import json
import zipfile

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
W, H = 1440, 1920
PAPER = '#F3F1EB'
INK = '#19201E'
MUTED = '#717770'
LINE = '#D5D8D0'
ORANGE = '#F25A35'
LIME = '#D5F18A'
FONT = '/System/Library/Fonts/Hiragino Sans GB.ttc'
LATIN = '/System/Library/Fonts/Avenir Next.ttc'
files = []


def font(size, bold=False, latin=False):
    return ImageFont.truetype(LATIN if latin else FONT, size,
                             index=(0 if bold else 7) if latin else (2 if bold else 0))


def text(x, y, s, size=32, color=INK, bold=False, latin=False):
    d.text((x, y), s, font=font(size, bold, latin), fill=color, anchor='lt')


def line(x1, y1, x2, y2, color=LINE, width=2):
    d.line((x1, y1, x2, y2), fill=color, width=width)


def box(rect, fill, radius=32, outline=None, width=2):
    d.rounded_rectangle(rect, radius, fill=fill, outline=outline, width=width)


def plus(cx, cy, size, color, width):
    line(cx-size, cy, cx+size, cy, color, width)
    line(cx, cy-size, cx, cy+size, color, width)


def arrow(x1, y, x2, color=INK, width=4):
    line(x1, y, x2, y, color, width)
    line(x2-15, y-12, x2, y, color, width)
    line(x2-15, y+12, x2, y, color, width)


def base(n, topic, dark=False):
    global im, d
    im = Image.new('RGB', (W, H), INK if dark else PAPER)
    d = ImageDraw.Draw(im)
    fg = PAPER if dark else INK
    text(88, 65, 'MiAIPlus', 37, fg, True, True)
    text(1060, 72, '1.0.0 / PREVIEW', 23, fg, latin=True)
    line(88, 129, 1352, 129, '#454D46' if dark else LINE)
    line(88, 1796, 1352, 1796, '#454D46' if dark else LINE)
    text(88, 1832, '超级小爱增强模块', 24, fg)
    text(960, 1836, topic, 22, fg)
    text(1293, 1832, f'{n:02d}', 27, fg, latin=True)


def save(name):
    path = OUT / name
    im.save(path, optimize=True)
    files.append(path)


# 01: A restrained cover: a strong typographic mark and one concrete promise.
base(1, '小爱 · 加一点本事')
text(88, 212, '让小爱，', 130, bold=True)
text(88, 379, '直接操作手机。', 130, bold=True)
text(95, 584, '手机操作 / 后台虚拟屏 / 自选模型', 35)
text(83, 770, 'MiAIPlus', 225, bold=True, latin=True)
d.ellipse((1120, 784, 1352, 1016), fill=ORANGE)
plus(1236, 900, 57, PAPER, 18)
line(88, 1084, 1352, 1084)
for x, k, a, b in [(88, '01', '直接操作手机', '打开、点击、滑动、输入'),
                       (527, '02', '任务放到后台', '独立虚拟屏执行'),
                       (966, '03', '模型自己选', '默认无需额外 API')]:
    text(x, 1143, k, 25, ORANGE, latin=True)
    text(x, 1200, a, 37, bold=True)
    text(x, 1269, b, 26, MUTED)
box((88, 1404, 1352, 1704), INK, 36)
text(140, 1454, '还是你熟悉的小爱。', 51, PAPER, True)
text(140, 1540, '在原有对话里，调用新增的手机操作工具。', 31, '#C8CEC6')
text(140, 1621, 'Android 13+  ·  LSPosed API 102  ·  指定小爱版本', 25, '#C8CEC6')
save('01-cover.png')


# 02: Honest conceptual illustration of two execution surfaces.
base(2, '后台虚拟屏', True)
text(88, 216, '主屏归你。', 128, PAPER, True)
text(88, 382, '任务去后台。', 128, LIME, True)
text(94, 578, '在独立虚拟屏执行，主屏可以继续用。', 36, '#C8CEC6')

# Graphic panels, deliberately diagrammatic rather than fake screenshots.
box((572, 730, 1352, 1265), '#303B32', 40, '#65755B')
text(620, 776, '后台 · 独立虚拟屏', 32, LIME, True)
line(620, 842, 1304, 842, '#54634D')
for j, (label, amount) in enumerate([('观察页面', 540), ('执行动作', 430), ('返回新观察', 300)]):
    yy = 895 + j*100
    d.ellipse((622, yy+3, 638, yy+19), fill=LIME)
    text(660, yy, label, 25, '#DCE7D2')
    box((660, yy+43, 660+amount, yy+49), '#586C47', 3)
text(1100, 1192, '执行流程示意', 20, '#A3B599')

box((88, 939, 535, 1508), PAPER, 40)
text(130, 985, '主屏 · 继续使用', 29, INK, True)
box((130, 1064, 493, 1220), '#E1E2D9', 24)
text(158, 1095, '你的手机', 41, INK, True)
text(158, 1162, '你继续用', 26, MUTED)
for yy, ww in [(1264, 303), (1303, 247), (1342, 283)]:
    box((130, yy, 130+ww, yy+12), '#D0D4CA', 6)
text(130, 1434, '屏幕关系示意', 20, MUTED)
line(536, 1380, 866, 1380, '#65755B', 3)
line(866, 1266, 866, 1380, '#65755B', 3)
text(915, 1356, '两块屏幕，各做各的事。', 29, PAPER)
text(88, 1595, '后台需 root；设备兼容性有差异。', 27, '#B5BFB1')
text(88, 1645, '虚拟屏不是应用分身，同一应用可能发生冲突。', 27, '#B5BFB1')
text(88, 1695, '需要切回前台时按冲突交接规则处理，故障不会静默抢占主屏。', 25, '#B5BFB1')
save('02-background.png')


# 03: Model freedom stated as real configuration choices, not vendor promises.
base(3, '模型与原生入口')
text(88, 218, '小爱照常用。', 116, bold=True)
text(88, 373, '模型，你来定。', 116, bold=True)
text(94, 556, '聊天和控屏，可以交给不同的模型。', 35, MUTED)
rows = [
    ('01', '直接用小爱当前模型', 'API 配置留空，也能开始控屏。', '无需额外 API'),
    ('02', '接入自己的模型服务', '保存多套配置，按需要切换。', 'Chat Completions / Responses'),
    ('03', '单独选一个控屏模型', '日常聊天照旧，控屏交给外部视觉模型。', '聊天 / 控屏，分开选'),
]
for i, (num, title, sub, tag) in enumerate(rows):
    yy = 690+i*278
    line(88, yy, 1352, yy)
    text(90, yy+40, num, 31, ORANGE, latin=True)
    text(188, yy+36, title, 49, bold=True)
    text(190, yy+112, sub, 29, MUTED)
    text(190, yy+176, tag, 25, ORANGE, latin=('Chat' in tag))
box((88, 1561, 1352, 1722), '#E4E7DC', 28)
text(124, 1590, '思考档位，也能在小爱原生菜单里选。', 36, bold=True)
text(124, 1651, '可选档位随模型而定；控屏模型需支持图片与工具调用。', 25, MUTED)
save('03-models.png')


# 04: Interaction surfaces and task-bound confirmation, explicitly a schematic.
base(4, '原生交互')
text(88, 218, '进度在岛上。', 116, bold=True)
text(88, 373, '决定在你手上。', 116, bold=True)
text(94, 559, '查看进度、停止任务、确认敏感操作。', 35, MUTED)
text(90, 688, '交互逻辑示意 · 非运行截图', 23, MUTED)
box((88, 757, 1352, 1190), '#E5E7DF', 40)
box((198, 817, 1242, 1116), '#171C19', 60)
d.ellipse((245, 865, 263, 883), fill=LIME)
text(286, 855, '等待确认', 27, '#D5F18A', True)
text(246, 915, '确认本次敏感操作？', 43, PAPER, True)
box((246, 1002, 707, 1073), PAPER, 35)
text(372, 1022, '确认本次操作', 27, INK, True)
box((738, 1002, 1192, 1073), '#343C36', 35)
text(904, 1022, '停止任务', 27, PAPER)
for yy, num, title, sub in [
    (1280, '01', '只确认这一次', '前台敏感操作逐次确认，拒绝或超时即停止。'),
    (1441, '02', '完成后，回到原对话', '点击完成通知，返回发起任务的那条对话。'),
    (1602, '03', '遇到限制，交给你接管', '锁屏、密码、验证码等场景保留操作边界。'),
]:
    text(89, yy, num, 29, ORANGE, latin=True)
    text(185, yy-3, title, 41, bold=True)
    text(187, yy+62, sub, 28, MUTED)
save('04-interaction.png')


# 05: Entry requirements are legible at feed scale, not hidden in a tiny disclaimer.
base(5, '安装前看这里')
text(88, 211, '想试？', 174, bold=True)
text(91, 456, '先对一下版本。', 64, bold=True)
box((88, 604, 1352, 840), ORANGE, 36)
text(132, 646, 'MiAIPlus', 67, PAPER, True, True)
text(132, 742, 'v1.0.0-preview / 预发布测试版', 33, PAPER)
for yy, title, sub in [
    (919, 'Android 13+', 'LSPosed：支持 API 102'),
    (1080, '超级小爱 · 精确适配', '8.2.65.4714  /  8.3.30.4420'),
    (1241, '后台虚拟屏需 root', '控屏需启用无障碍；不同设备兼容性有差异。'),
]:
    line(88, yy-33, 1352, yy-33)
    text(90, yy, title, 43, bold=True, latin=(title=='Android 13+'))
    text(92, yy+69, sub, 30, MUTED)
line(88, 1403, 1352, 1403)
text(90, 1456, '项目地址 / 安装说明', 27, MUTED)
text(90, 1515, 'github.com/astoHu1/MiAIPlus', 48, bold=True, latin=True)
arrow(1215, 1546, 1335, ORANGE, 5)
text(90, 1625, '自动操作仍可能出错，请留意执行过程。', 28, MUTED)
text(90, 1676, '默认使用官方模型；外接服务可选，控屏观察会发送给所选模型。', 25, MUTED)
save('05-get-started.png')


# A small contact sheet is for review; only the five full-size PNGs go in the ZIP.
thumbs = Image.new('RGB', (1440, 820), '#DADCD4')
td = ImageDraw.Draw(thumbs)
for i, path in enumerate(files):
    pic = Image.open(path)
    pic.thumbnail((272, 363), Image.Resampling.LANCZOS)
    x = 16+i*286
    thumbs.paste(pic, (x, 40))
    td.text((x, 420), f'{i+1:02d}', fill=INK, font=font(24, latin=True))
td.text((28, 538), 'MiAIPlus', fill=INK, font=font(65, True, True))
td.text((30, 638), '酷安图文 / 5 张竖版 / 1440 × 1920', fill=INK, font=font(30))
td.text((30, 708), '纯排版与功能示意，无虚构运行截图。', fill=MUTED, font=font(25))
thumbs.save(OUT / 'contact-sheet.jpg', quality=94)
with zipfile.ZipFile(OUT / 'MiAIPlus-coolapk-5-posters.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for path in files:
        z.write(path, path.name)
print(json.dumps({'images': [str(p) for p in files], 'size': [W, H]}, ensure_ascii=False))
