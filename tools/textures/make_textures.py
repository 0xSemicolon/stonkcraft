"""
HyperCraft's 8-bit art: every block and item texture, and the market logos, drawn in code so they share one
palette and one material language (bevelled graphite, gold trim, lit glass).

    python tools/textures/make_textures.py            # writes into src/main/resources/assets/hypercraft/textures
    python tools/textures/make_textures.py --sheet out.png   # also a contact sheet for review
"""
import math
import os
import sys

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'assets', 'hypercraft', 'textures')


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def shade(c, k):
    """k > 0 lightens towards white, k < 0 darkens towards black."""
    return mix(c, (255, 255, 255), k) if k > 0 else mix(c, (0, 0, 0), -k)


# ---------------------------------------------------------------- palette
GRAPHITE = hexc('#1b2327')
GRAPHITE_HI = hexc('#33424a')
GRAPHITE_LO = hexc('#0c1113')
PANEL = hexc('#232e33')
GOLD = hexc('#f2c14e')
GOLD_HI = hexc('#ffe9a3')
GOLD_LO = hexc('#9c6a17')
MINT = hexc('#5ef0c3')
MINT_LO = hexc('#1f8f74')
GLASS = hexc('#071214')
GLASS_HI = hexc('#16343a')
RED = hexc('#ff4d5e')
GREEN = hexc('#3fe0a6')
WHITE = hexc('#f4f8f7')
CLEAR = (0, 0, 0, 0)


class Canvas:
    def __init__(self, w=16, h=16, fill=CLEAR):
        self.im = Image.new('RGBA', (w, h), fill)
        self.w, self.h = w, h

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            if len(c) == 4 and c[3] < 255 and c[3] > 0:
                base = self.im.getpixel((x, y))
                t = c[3] / 255
                c = tuple(int(base[i] * (1 - t) + c[i] * t) for i in range(3)) + (max(base[3], c[3]),)
            self.im.putpixel((x, y), c)

    def get(self, x, y):
        return self.im.getpixel((x, y))

    def rect(self, x1, y1, x2, y2, c):
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                self.px(x, y, c)

    def frame(self, x1, y1, x2, y2, c):
        for x in range(x1, x2 + 1):
            self.px(x, y1, c)
            self.px(x, y2, c)
        for y in range(y1, y2 + 1):
            self.px(x1, y, c)
            self.px(x2, y, c)

    def bitmap(self, x0, y0, rows, colors):
        """rows: strings; colors: char -> colour ('.' and ' ' are transparent)."""
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch in colors:
                    self.px(x0 + dx, y0 + dy, colors[ch])

    def save(self, rel):
        path = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.im.save(path)
        return self.im


# ---------------------------------------------------------------- 5x7 pixel font
FONT = {
    'A': ['.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
    'B': ['####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.'],
    'C': ['.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.'],
    'D': ['####.', '#...#', '#...#', '#...#', '#...#', '#...#', '####.'],
    'E': ['#####', '#....', '#....', '####.', '#....', '#....', '#####'],
    'F': ['#####', '#....', '#....', '####.', '#....', '#....', '#....'],
    'G': ['.###.', '#...#', '#....', '#.###', '#...#', '#...#', '.####'],
    'H': ['#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
    'I': ['###', '.#.', '.#.', '.#.', '.#.', '.#.', '###'],
    'J': ['..###', '...#.', '...#.', '...#.', '#..#.', '#..#.', '.##..'],
    'K': ['#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#'],
    'L': ['#....', '#....', '#....', '#....', '#....', '#....', '#####'],
    'M': ['#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#'],
    'N': ['#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#', '#...#'],
    'O': ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    'P': ['####.', '#...#', '#...#', '####.', '#....', '#....', '#....'],
    'Q': ['.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#'],
    'R': ['####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#'],
    'S': ['.####', '#....', '#....', '.###.', '....#', '....#', '####.'],
    'T': ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
    'U': ['#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    'V': ['#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..'],
    'W': ['#...#', '#...#', '#...#', '#.#.#', '#.#.#', '##.##', '#...#'],
    'X': ['#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#'],
    'Y': ['#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..'],
    'Z': ['#####', '....#', '...#.', '..#..', '.#...', '#....', '#####'],
    '0': ['.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.'],
    '1': ['.#.', '##.', '.#.', '.#.', '.#.', '.#.', '###'],
    '2': ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'],
    '3': ['####.', '....#', '....#', '.###.', '....#', '....#', '####.'],
    '5': ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
    '$': ['..#..', '.####', '#.#..', '.###.', '..#.#', '####.', '..#..'],
    '%': ['##..#', '##.#.', '...#.', '..#..', '.#...', '.#.##', '#..##'],
    'x': ['.....', '.....', '#...#', '.#.#.', '..#..', '.#.#.', '#...#'],
}


def text_width(s):
    return sum(len(FONT[ch][0]) + 1 for ch in s) - 1


def draw_text(cv, x, y, s, c, shadow=None):
    for ch in s:
        g = FONT[ch]
        if shadow:
            cv.bitmap(x + 1, y + 1, g, {'#': shadow})
        cv.bitmap(x, y, g, {'#': c})
        x += len(g[0]) + 1


# ---------------------------------------------------------------- materials

def graphite(cv, x1=0, y1=0, x2=15, y2=15, base=GRAPHITE):
    """Brushed graphite panel: faint horizontal grain, a lit top-left bevel and a shadowed bottom-right one."""
    for y in range(y1, y2 + 1):
        for x in range(x1, x2 + 1):
            grain = ((x * 7 + y * 13) % 11) / 11.0
            k = (grain - 0.5) * 0.06 + (0.03 if y % 3 == 0 else 0)
            cv.px(x, y, shade(base, k))
    for x in range(x1, x2 + 1):
        cv.px(x, y1, GRAPHITE_HI)
        cv.px(x, y2, GRAPHITE_LO)
    for y in range(y1, y2 + 1):
        cv.px(x1, y, GRAPHITE_HI)
        cv.px(x2, y, GRAPHITE_LO)


def gold_corners(cv, inset=1, length=3):
    """Gold L-brackets in each corner: the HyperCraft trim."""
    n = cv.w - 1 - inset
    for i in range(length):
        for (x, y) in [(inset + i, inset), (inset, inset + i), (n - i, inset), (n, inset + i),
                       (inset + i, n), (inset, n - i), (n - i, n), (n, n - i)]:
            cv.px(x, y, GOLD)
    for (x, y) in [(inset, inset), (n, inset)]:
        cv.px(x, y, GOLD_HI)
    for (x, y) in [(inset, n), (n, n)]:
        cv.px(x, y, GOLD_LO)


def rivets(cv, coords):
    for (x, y) in coords:
        cv.px(x, y, GRAPHITE_HI)
        cv.px(x + 1, y + 1, GRAPHITE_LO)


def screen(cv, x1, y1, x2, y2, bezel=GOLD):
    """A recessed glass screen with a thin gold bezel and a diagonal glare."""
    cv.frame(x1, y1, x2, y2, bezel)
    cv.px(x1, y1, GOLD_HI)
    cv.px(x2, y2, GOLD_LO)
    for y in range(y1 + 1, y2):
        for x in range(x1 + 1, x2):
            t = (y - y1) / max(1, (y2 - y1))
            cv.px(x, y, mix(GLASS_HI, GLASS, t))
    for i in range(3):
        cv.px(x1 + 2 + i, y1 + 1, (255, 255, 255, 40))


def standard_side(name, extra=None):
    cv = Canvas()
    graphite(cv)
    gold_corners(cv)
    if extra:
        extra(cv)
    return cv


# ---------------------------------------------------------------- the HyperCraft mark

MARK = ['..#..', '.###.', '##.##', '.###.', '..#..']


def mark(cv, x, y, c=MINT):
    cv.bitmap(x, y, MARK, {'#': c})


# ---------------------------------------------------------------- blocks

def blocks():
    out = {}

    # casing, shared sides
    cv = standard_side('casing')
    rivets(cv, [(3, 7), (11, 7)])
    out['block/casing'] = cv.save('block/casing.png')

    # trading terminal: a candlestick chart over the HyperCraft mark
    cv = Canvas()
    graphite(cv)
    screen(cv, 1, 1, 14, 11)
    candles = [(3, 7, 9, GREEN), (5, 5, 8, GREEN), (7, 6, 9, RED), (9, 3, 7, GREEN), (11, 2, 5, GREEN), (13, 3, 6, RED)]
    for x, top, bot, c in candles:
        if x >= 14:
            continue
        cv.px(x, top - 1, shade(c, -0.4))
        for y in range(top, bot + 1):
            cv.px(x, y, c)
        cv.px(x, bot + 1, shade(c, -0.4))
    for x in range(2, 14):
        cv.px(x, 10, (94, 240, 195, 70))
    mark(cv, 2, 12, GOLD)
    cv.rect(8, 13, 13, 13, MINT_LO)
    cv.px(13, 13, MINT)
    out['block/terminal_front'] = cv.save('block/terminal_front.png')
    cv = standard_side('terminal_side', lambda c: [c.rect(4, 6 + i * 2, 11, 6 + i * 2, GRAPHITE_LO) for i in range(3)])
    out['block/terminal_side'] = cv.save('block/terminal_side.png')
    cv = standard_side('terminal_top', lambda c: (c.rect(5, 6, 10, 9, GOLD_LO), c.rect(5, 6, 10, 8, GOLD), mark(c, 6, 5, GOLD_HI)))
    out['block/terminal_top'] = cv.save('block/terminal_top.png')

    # ticker display: a black LED panel in a gold bezel (the renderer draws the logo, price and spark line)
    cv = Canvas()
    graphite(cv)
    cv.frame(0, 0, 15, 15, GOLD_LO)
    cv.frame(1, 1, 14, 14, GOLD)
    for y in range(2, 14):
        for x in range(2, 14):
            cv.px(x, y, (4, 9, 10, 255) if (x + y) % 2 else (7, 14, 16, 255))
    cv.px(1, 1, GOLD_HI)
    out['block/ticker_front'] = cv.save('block/ticker_front.png')
    cv = standard_side('ticker_side', lambda c: [c.px(x, 13, MINT if x % 3 else MINT_LO) for x in range(4, 12)])
    out['block/ticker_side'] = cv.save('block/ticker_side.png')

    # account display: same panel, mint bezel
    cv = Canvas()
    graphite(cv)
    cv.frame(1, 1, 14, 14, MINT_LO)
    cv.frame(0, 0, 15, 15, GRAPHITE_LO)
    for y in range(2, 14):
        for x in range(2, 14):
            cv.px(x, y, (4, 9, 10, 255) if (x + y) % 2 else (7, 14, 16, 255))
    cv.px(1, 1, MINT)
    out['block/account_display_front'] = cv.save('block/account_display_front.png')

    # market sensor: a lens, dim and lit
    for on in (False, True):
        cv = Canvas()
        graphite(cv)
        gold_corners(cv)
        core = MINT if on else MINT_LO
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if d < 4.6:
                    cv.px(x, y, GRAPHITE_LO)
                if d < 3.6:
                    cv.px(x, y, mix(core, GLASS, min(1, d / 3.6)))
                if on and 4.6 <= d < 5.6:
                    cv.px(x, y, (94, 240, 195, 90))
        cv.px(6, 6, WHITE if on else shade(MINT_LO, 0.3))
        out['block/sensor_side' + ('_on' if on else '')] = cv.save('block/sensor_side' + ('_on' if on else '') + '.png')
        cv = Canvas()
        graphite(cv)
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if abs(d - 6) < 0.5 or abs(d - 3.5) < 0.5:
                    cv.px(x, y, MINT if on else MINT_LO)
        sweep = math.radians(-50)
        for r in range(1, 7):
            cv.px(int(7.5 + r * math.cos(sweep)), int(7.5 + r * math.sin(sweep)), MINT if on else MINT_LO)
        cv.px(7, 7, WHITE if on else MINT_LO)
        out['block/sensor_top' + ('_on' if on else '')] = cv.save('block/sensor_top' + ('_on' if on else '') + '.png')

    # trade executor: buy and sell arrows; the top is a gold coin
    up = ['..#..', '.###.', '#####', '..#..', '..#..', '..#..', '..#..']
    for on in (False, True):
        cv = Canvas()
        graphite(cv)
        gold_corners(cv)
        g = GREEN if on else shade(GREEN, -0.45)
        r = RED if on else shade(RED, -0.45)
        cv.bitmap(2, 4, up, {'#': g})
        cv.bitmap(9, 5, list(reversed(up)), {'#': r})
        if on:
            cv.rect(2, 13, 13, 13, GOLD)
        out['block/executor_side' + ('_on' if on else '')] = cv.save('block/executor_side' + ('_on' if on else '') + '.png')
        cv = Canvas()
        graphite(cv)
        coin_disc(cv, GOLD if on else shade(GOLD, -0.35))
        draw_text(cv, 6, 4, '$', GOLD_LO if on else shade(GOLD_LO, -0.3))
        out['block/executor_top' + ('_on' if on else '')] = cv.save('block/executor_top' + ('_on' if on else '') + '.png')

    # kill switch: a red mushroom button inside hazard stripes
    for on in (False, True):
        cv = Canvas()
        for y in range(16):
            for x in range(16):
                cv.px(x, y, GOLD if ((x + y) // 2) % 2 == 0 else GRAPHITE_LO)
        cv.rect(2, 2, 13, 13, GRAPHITE)
        button = RED if on else shade(RED, -0.35)
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if d < 5:
                    cv.px(x, y, shade(button, -0.35 if d > 4 else (0.25 if x + y < 13 else 0)))
        cv.px(5, 5, WHITE)
        out['block/kill_switch' + ('_on' if on else '')] = cv.save('block/kill_switch' + ('_on' if on else '') + '.png')
    cv = standard_side('kill_switch_top', lambda c: draw_text(c, 4, 4, 'X', RED, GRAPHITE_LO))
    out['block/kill_switch_top'] = cv.save('block/kill_switch_top.png')

    # leverage dial: a gauge, needle low / mid / high
    for name, angle in (('leverage_dial', 200), ('leverage_dial_mid', 270), ('leverage_dial_high', 335)):
        cv = Canvas()
        graphite(cv)
        gold_corners(cv)
        for a in range(180, 361, 6):
            t = (a - 180) / 180
            c = mix(GREEN, GOLD, t * 2) if t < 0.5 else mix(GOLD, RED, (t - 0.5) * 2)
            rad = math.radians(a)
            cv.px(int(round(7.5 + 6 * math.cos(rad))), int(round(10 + 6 * math.sin(rad))), c)
        rad = math.radians(angle)
        for r in range(0, 6):
            cv.px(int(round(7.5 + r * math.cos(rad))), int(round(10 + r * math.sin(rad))), WHITE)
        cv.rect(6, 10, 9, 11, GOLD)
        draw_text(cv, 6, 12, 'x', GOLD_HI) if False else None
        out['block/' + name] = cv.save('block/' + name + '.png')
    cv = standard_side('leverage_dial_top', lambda c: (c.rect(5, 7, 10, 8, GOLD), c.px(7, 6, GOLD_HI)))
    out['block/leverage_dial_top'] = cv.save('block/leverage_dial_top.png')

    # account vault: a display strip over a safe door with a gold combination dial and a handle
    cv = Canvas()
    graphite(cv, base=hexc('#2a363c'))
    cv.frame(0, 0, 15, 15, GRAPHITE_LO)
    screen(cv, 1, 1, 14, 8)
    cv.frame(2, 9, 13, 14, GRAPHITE_HI)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 6.5, y - 11.8)
            if d < 2.4:
                cv.px(x, y, GOLD if d > 1.3 else GOLD_LO)
    cv.px(6, 11, GOLD_HI)
    cv.rect(10, 11, 12, 11, GOLD)
    cv.px(12, 12, GOLD_LO)
    cv.px(3, 10, GOLD_LO)
    cv.px(3, 13, GOLD_LO)
    out['block/vault_front'] = cv.save('block/vault_front.png')
    cv = standard_side('vault_side', lambda c: [c.rect(2, y, 13, y, GRAPHITE_LO) for y in (4, 8, 12)])
    rivets(cv, [(2, 2), (12, 2), (2, 12), (12, 12)])
    out['block/vault_side'] = cv.save('block/vault_side.png')
    cv = standard_side('vault_top', lambda c: (c.rect(3, 6, 12, 9, GOLD_LO), c.rect(3, 6, 12, 8, GOLD), c.rect(4, 6, 11, 6, GOLD_HI)))
    out['block/vault_top'] = cv.save('block/vault_top.png')

    # UNIT.XYZ: a gold bar and a copper bar behind glass, the UNIT mark above
    copper, copper_hi, copper_lo = hexc('#d9844a'), hexc('#f5b98a'), hexc('#8a4a22')
    small = {'U': ['#.#', '#.#', '#.#', '#.#', '###'], 'N': ['##.', '#.#', '#.#', '#.#', '#.#'], 'I': ['###', '.#.', '.#.', '.#.', '###'],
             'T': ['###', '.#.', '.#.', '.#.', '.#.'], 'X': ['#.#', '#.#', '.#.', '#.#', '#.#'], 'Y': ['#.#', '#.#', '.#.', '.#.', '.#.'],
             'Z': ['###', '..#', '.#.', '#..', '###']}
    cv = Canvas()
    graphite(cv)
    for i, ch in enumerate('UNIT'):
        cv.bitmap(1 + i * 4, 2, small[ch], {'#': GOLD_LO})
        cv.bitmap(0 + i * 4 + 1, 1, small[ch], {'#': GOLD})
        cv.px(1 + i * 4, 1, GOLD_HI)
    for i, ch in enumerate('XYZ'):
        cv.bitmap(3 + i * 4, 7, small[ch], {'#': MINT_LO})
        cv.bitmap(3 + i * 4, 6, small[ch], {'#': MINT})
    screen(cv, 1, 11, 14, 15)
    bar(cv, 2, 12, 5, GOLD, GOLD_HI, GOLD_LO)
    bar(cv, 9, 12, 5, copper, copper_hi, copper_lo)
    out['block/unit_front'] = cv.save('block/unit_front.png')
    cv = standard_side('unit_side', lambda c: (bar(c, 3, 5, 10, GOLD, GOLD_HI, GOLD_LO), bar(c, 3, 9, 10, copper, copper_hi, copper_lo)))
    out['block/unit_side'] = cv.save('block/unit_side.png')
    cv = Canvas()
    graphite(cv)
    gold_corners(cv)
    for i in range(5):
        cv.frame(2 + i, 2 + i, 13 - i, 13 - i, mix(GOLD, GRAPHITE_LO, i / 4))
    out['block/unit_top'] = cv.save('block/unit_top.png')

    # signal mixer: two waves joining into one
    purple, purple_hi, purple_lo = hexc('#b46cf0'), hexc('#e4c2ff'), hexc('#5a2a86')
    cv = Canvas()
    graphite(cv)
    gold_corners(cv)
    screen(cv, 1, 2, 14, 13, purple_lo)
    for x in range(2, 14):
        y1 = int(round(5 + 1.5 * math.sin(x * 0.9)))
        y2 = int(round(10 + 1.5 * math.sin(x * 0.9 + 2)))
        cv.px(x, y1, MINT)
        cv.px(x, y2, purple)
        if x > 9:
            cv.px(x, 7 + (x % 2), purple_hi)
    out['block/mixer_side'] = cv.save('block/mixer_side.png')
    cv = Canvas()
    graphite(cv)
    for (a, b) in [((3, 3), (8, 8)), ((12, 3), (8, 8)), ((3, 12), (8, 8)), ((8, 8), (12, 12))]:
        for i in range(11):
            t = i / 10
            cv.px(int(round(a[0] + (b[0] - a[0]) * t)), int(round(a[1] + (b[1] - a[1]) * t)), purple_lo)
    for (x, y, c) in [(3, 3, MINT), (12, 3, MINT), (3, 12, MINT), (12, 12, GOLD), (8, 8, purple_hi)]:
        cv.rect(x - 1, y - 1, x, y, c)
    out['block/mixer_top'] = cv.save('block/mixer_top.png')

    # copy desk: a leader's chart echoed on the follower's
    blue, blue_lo = hexc('#7fd3ff'), hexc('#2a5f7f')
    cv = Canvas()
    graphite(cv)
    screen(cv, 1, 1, 7, 8, blue_lo)
    screen(cv, 8, 1, 14, 8, blue_lo)
    for i, y in enumerate([6, 5, 6, 4, 3]):
        cv.px(2 + i, y, GREEN)
        cv.px(9 + i, y, blue)
    cv.bitmap(6, 10, ['#..', '###', '#..'], {'#': GOLD})
    cv.rect(2, 13, 13, 13, blue_lo)
    gold_corners(cv)
    out['block/desk_front'] = cv.save('block/desk_front.png')
    cv = standard_side('desk_side', lambda c: [c.px(x, 8, blue if x % 2 else blue_lo) for x in range(3, 13)])
    out['block/desk_side'] = cv.save('block/desk_side.png')
    cv = standard_side('desk_top', lambda c: (c.rect(3, 4, 12, 11, PANEL), [c.rect(4, y, 10, y, WHITE) for y in (5, 7, 9)]))
    out['block/desk_top'] = cv.save('block/desk_top.png')
    return out


def bar(cv, x, y, w, c, hi, lo):
    """A metal ingot seen from the front: a bevelled trapezoid."""
    cv.rect(x + 1, y, x + w - 2, y, hi)
    cv.rect(x, y + 1, x + w - 1, y + 2, c)
    cv.rect(x, y + 3, x + w - 1, y + 3, lo)
    cv.px(x + 1, y + 1, hi)


def coin_disc(cv, c, cx=7.5, cy=7.5, r=6.6):
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= r:
                k = 0.0
                if d > r - 1.1:
                    k = -0.35
                elif (x - cx) + (y - cy) < -3:
                    k = 0.3
                elif (x - cx) + (y - cy) > 4:
                    k = -0.15
                cv.px(x, y, shade(c, k))


# ---------------------------------------------------------------- items

def items():
    out = {}
    usdc = hexc('#2775ca')
    # $1 chip: a poker chip in USDC blue with white edge spots
    cv = Canvas()
    coin_disc(cv, usdc)
    for a in range(0, 360, 60):
        rad = math.radians(a)
        cv.px(int(round(7.5 + 5.8 * math.cos(rad))), int(round(7.5 + 5.8 * math.sin(rad))), WHITE)
    draw_text(cv, 6, 4, '$', WHITE, shade(usdc, -0.5))
    out['item/usdc_chip'] = cv.save('item/usdc_chip.png')
    # $100 note: a green banknote, portrait oval and corner numerals
    note, note_hi, note_lo = hexc('#3f9b5a'), hexc('#a8e0b4'), hexc('#1f5a33')
    cv = Canvas()
    cv.rect(0, 4, 15, 11, note)
    cv.frame(0, 4, 15, 11, note_lo)
    cv.frame(1, 5, 14, 10, note_hi)
    cv.rect(6, 6, 9, 9, note_hi)
    cv.rect(7, 6, 8, 9, note_lo)
    cv.px(2, 6, note_hi)
    cv.px(13, 9, note_hi)
    out['item/usdc_note'] = cv.save('item/usdc_note.png')
    # $10,000 bar: a gold ingot stamped with $
    cv = Canvas()
    for y in range(6, 13):
        inset = max(0, 8 - y) if y < 8 else 0
        for x in range(1 + inset, 15 - inset):
            cv.px(x, y, GOLD_HI if y == 6 else GOLD_LO if y == 12 else GOLD)
    cv.rect(2, 8, 13, 8, GOLD_HI)
    draw_text(cv, 6, 6, '$', GOLD_LO) if False else cv.bitmap(6, 8, ['.#.', '###', '.#.'], {'#': GOLD_LO})
    out['item/usdc_bar'] = cv.save('item/usdc_bar.png')
    # account card: graphite card, gold chip, mint stripe
    cv = Canvas()
    cv.rect(0, 3, 15, 12, GRAPHITE)
    cv.frame(0, 3, 15, 12, GRAPHITE_HI)
    cv.rect(0, 5, 15, 5, GRAPHITE_LO)
    cv.rect(2, 7, 5, 10, GOLD)
    cv.rect(3, 8, 4, 9, GOLD_LO)
    cv.rect(8, 10, 13, 10, MINT)
    mark(cv, 9, 6, MINT)
    out['item/account_card'] = cv.save('item/account_card.png')
    # signal card: purple card with a wave
    purple, purple_hi = hexc('#7a3fb8'), hexc('#e4c2ff')
    cv = Canvas()
    cv.rect(0, 3, 15, 12, purple)
    cv.frame(0, 3, 15, 12, purple_hi)
    for x in range(2, 14):
        cv.px(x, int(round(7.5 + 2 * math.sin(x * 0.8))), MINT)
    cv.rect(2, 10, 4, 10, GOLD)
    out['item/signal_card'] = cv.save('item/signal_card.png')
    return out


# ---------------------------------------------------------------- market logos
# Only markets that trade on Hyperliquid or trade.xyz get a logo; delisted ones (DXY, VIX, NIFTY, IBOV, SNAP, TON, TTF, TTWO) don't.

def badge(c, ring=None):
    cv = Canvas()
    coin_disc(cv, c, r=7.4)
    if ring:
        for y in range(16):
            for x in range(16):
                if abs(math.hypot(x - 7.5, y - 7.5) - 7.0) < 0.5:
                    cv.px(x, y, ring)
    return cv


def glyph(cv, rows, colors, x=None, y=None, shadow=None):
    w, h = max(len(r) for r in rows), len(rows)
    x = (16 - w) // 2 if x is None else x
    y = (16 - h) // 2 if y is None else y
    if shadow:
        cv.bitmap(x + 1, y + 1, rows, {k: shadow for k in colors})
    cv.bitmap(x, y, rows, colors)


def letters(cv, s, c, shadow=None, y=None):
    w = text_width(s)
    draw_text(cv, (16 - w) // 2, (16 - 7) // 2 if y is None else y, s, c, shadow)


def logos():
    L = {}

    def save(name, cv):
        L[name] = cv.save('logo/' + name.lower() + '.png')

    # --- crypto
    cv = badge(hexc('#f7931a'))
    glyph(cv, ['..#.#..', '######.', '.##..##', '.#####.', '.##..##', '.##..##', '######.', '..#.#..'],
          {'#': WHITE}, shadow=hexc('#9a5200'))
    save('BTC', cv)
    cv = badge(hexc('#454a75'))
    glyph(cv, ['...#...', '..###..', '.##W##.', '##WWW##', '#WWWWW#', '.#WWW#.', '..#W#..', '#..#..#', '.#.#.#.', '..###..', '...#...'],
          {'#': hexc('#c4c8f2'), 'W': hexc('#8a90d4')})
    save('ETH', cv)
    cv = badge(hexc('#111111'))
    for i, y in enumerate((4, 7, 10)):
        for dx in range(9):
            c = mix(hexc('#9945ff'), hexc('#14f195'), dx / 8)
            off = 1 if i == 1 else 0
            cv.px(3 + dx + (1 - off), y, c)
            cv.px(4 + dx - off, y + 1, shade(c, -0.25))
    save('SOL', cv)
    cv = badge(hexc('#0a2a24'), hexc('#97fce4'))
    glyph(cv, ['.##....##.', '#..#..#..#', '#...##...#', '#..#..#..#', '.##....##.'], {'#': hexc('#97fce4')})
    save('HYPE', cv)
    cv = badge(hexc('#c2a633'))
    glyph(cv, ['.####..', '.#...#.', '.#....#', '####..#', '.#....#', '.#...#.', '.####..'], {'#': WHITE}, shadow=hexc('#6b5810'))
    save('DOGE', cv)
    cv = badge(hexc('#23292f'))
    glyph(cv, ['#.....#', '##...##', '.#####.', '.......', '.#####.', '##...##', '#.....#'], {'#': WHITE})
    save('XRP', cv)
    cv = badge(hexc('#1e2026'))
    glyph(cv, ['...#...', '..#.#..', '.#.#.#.', '#.###.#', '.#.#.#.', '..#.#..', '...#...'], {'#': hexc('#f3ba2f')})
    save('BNB', cv)
    cv = badge(hexc('#e84142'))
    glyph(cv, ['...#...', '..###..', '..###..', '.#####.', '.##.##.', '###..##'], {'#': WHITE})
    save('AVAX', cv)
    cv = badge(hexc('#2a5ada'))
    glyph(cv, ['..##..', '.#..#.', '#....#', '#....#', '.#..#.', '..##..'], {'#': WHITE})
    save('LINK', cv)
    cv = badge(hexc('#4da2ff'))
    glyph(cv, ['...#...', '..###..', '.#####.', '##.####', '##..###', '.#####.', '..###..'], {'#': WHITE})
    save('SUI', cv)
    cv = badge(hexc('#0033ad'))
    for (x, y) in [(7, 3), (4, 5), (10, 5), (7, 7), (4, 9), (10, 9), (7, 11), (5, 7), (9, 7)]:
        cv.px(x, y, WHITE)
        cv.px(x + 1, y, WHITE)
    save('ADA', cv)
    cv = badge(hexc('#97fce4'))
    glyph(cv, ['#.....#', '##...##', '#######', '#.###.#', '#######', '##...##', '.#####.'], {'#': hexc('#0a2a24')})
    save('PURR', cv)
    cv = badge(hexc('#4c9a2a'))
    glyph(cv, ['.##.##.', '#WK#WK#', '#######', '#######', '#RRRRR#', '.#####.'],
          {'#': hexc('#7bd34b'), 'W': WHITE, 'K': hexc('#111111'), 'R': hexc('#b0483e')})
    save('PEPE', cv)
    cv = badge(hexc('#2775ca'))
    glyph(cv, FONT['$'], {'#': WHITE}, shadow=hexc('#123c6b'))
    save('USDC', cv)
    for sym, col, s in (('LTC', '#345d9d', 'L'), ('DOT', '#e6007a', 'D'), ('TRX', '#ff060a', 'T'), ('ARB', '#2d374b', 'A'),
                        ('OP', '#ff0420', 'OP'), ('APT', '#111111', 'A'), ('NEAR', '#111111', 'N'), ('TAO', '#111111', 'T'),
                        ('ENA', '#1a1a1a', 'E'), ('WIF', '#c89b6d', 'W'), ('ZEC', '#f4b728', 'Z'),
                        ('XMR', '#ff6600', 'M'), ('AAVE', '#9a5cc6', 'A'), ('UNI', '#ff007a', 'U'), ('INJ', '#0082fa', 'I'),
                        ('BCH', '#8dc351', 'B'), ('ETC', '#328332', 'E'), ('FET', '#1f2a44', 'F'), ('PAXG', '#e4c15a', 'P')):
        cv = badge(hexc(col))
        letters(cv, s, WHITE, shade(hexc(col), -0.5))
        save(sym, cv)

    # --- stocks
    cv = badge(hexc('#f2f2f2'))
    glyph(cv, ['.....#...', '....#....', '.##.#.##.', '#########', '########.', '########.', '#########', '.#######.', '..##.##..'],
          {'#': hexc('#1d1d1f')})
    save('AAPL', cv)
    cv = badge(hexc('#111111'))
    glyph(cv, ['..#####.', '.##...##', '##.###.#', '#.##.#.#', '##.###.#', '.##...##', '..#####.'], {'#': hexc('#76b900')})
    save('NVDA', cv)
    cv = badge(hexc('#e31937'))
    glyph(cv, ['#######', '#.....#', '...#...', '...#...', '...#...', '...#...', '...#...'], {'#': WHITE})
    save('TSLA', cv)
    cv = badge(hexc('#f2f2f2'))
    cv.rect(3, 3, 7, 7, hexc('#f25022'))
    cv.rect(8, 3, 12, 7, hexc('#7fba00'))
    cv.rect(3, 8, 7, 12, hexc('#00a4ef'))
    cv.rect(8, 8, 12, 12, hexc('#ffb900'))
    save('MSFT', cv)
    cv = badge(hexc('#232f3e'))
    letters(cv, 'A', WHITE, y=3)
    glyph(cv, ['#........#', '.##....##.', '...####.#.'], {'#': hexc('#ff9900')}, y=10)
    save('AMZN', cv)
    cv = badge(hexc('#f2f2f2'))
    glyph(cv, ['..RRRR.', '.R....R', 'B......', 'B...YYY', 'B.....G', '.G....G', '..GGGG.'],
          {'R': hexc('#ea4335'), 'B': hexc('#4285f4'), 'Y': hexc('#4285f4'), 'G': hexc('#34a853')})
    save('GOOGL', cv)
    cv = badge(hexc('#f2f2f2'))
    glyph(cv, ['.##....##.', '#..#..#..#', '#...##...#', '#..#..#..#', '.##....##.'], {'#': hexc('#0668e1')})
    save('META', cv)
    cv = badge(hexc('#111111'))
    glyph(cv, FONT['N'], {'#': hexc('#e50914')}, shadow=hexc('#5a0006'))
    save('NFLX', cv)
    cv = badge(hexc('#111111'))
    glyph(cv, ['..#####', '...####', '..#..##', '.##..##', '####..#', '#####..'], {'#': WHITE})
    save('AMD', cv)
    cv = badge(hexc('#101113'))
    glyph(cv, ['.###.', '#...#', '#...#', '.###.', '.....', '#####'], {'#': WHITE})
    save('PLTR', cv)
    cv = badge(hexc('#0052ff'))
    glyph(cv, ['.####.', '#....#', '#.....', '#.....', '#....#', '.####.'], {'#': WHITE})
    save('COIN', cv)
    cv = badge(hexc('#ccff00'))
    glyph(cv, ['....##', '...##.', '..##..', '.##...', '##....', '#.....'], {'#': hexc('#111111')})
    save('HOOD', cv)
    cv = badge(hexc('#c74634'))
    glyph(cv, ['.######.', '#......#', '#......#', '.######.'], {'#': WHITE})
    save('ORCL', cv)
    for sym, col, s in (('INTC', '#0068b5', 'I'), ('MU', '#1d4b9c', 'MU'), ('TSM', '#cc0000', 'T'), ('LLY', '#d52b1e', 'L'),
                        ('COST', '#e31837', 'C'), ('BABA', '#ff6a00', 'B'), ('GME', '#111111', 'G'), ('MSTR', '#f7931a', 'M'),
                        ('CRCL', '#2775ca', 'C'), ('AVGO', '#cc092f', 'B'), ('IBM', '#0f62fe', 'IB'), ('ARM', '#0091bd', 'A'),
                        ('ASML', '#10069f', 'A'), ('DELL', '#007db8', 'D'), ('QCOM', '#3253dc', 'Q'), ('RKLB', '#111111', 'R'),
                        ('EBAY', '#e53238', 'E'), ('CRWD', '#e01e2c', 'C'), ('NET', '#f38020', 'N'),
                        ('RDDT', '#ff4500', 'R'), ('HIMS', '#5b8a72', 'H'), ('DKNG', '#53d337', 'D'), ('MRVL', '#d62e2e', 'M'),
                        ('BX', '#111111', 'BX'), ('NOW', '#62d84e', 'N'), ('ZM', '#2d8cff', 'Z'), ('CVX', '#0054a4', 'C'),
                        ('SMSN', '#1428a0', 'S'), ('SOFTBANK', '#c0c0c0', 'S'), ('HYUNDAI', '#002c5f', 'H'), ('GEV', '#3b8686', 'G'),
                        ('LRCX', '#0076c0', 'L'), ('AMAT', '#3a5ba0', 'A')):
        cv = badge(hexc(col))
        fg = hexc('#111111') if sum(hexc(col)[:3]) > 520 else WHITE
        letters(cv, s, fg, shade(hexc(col), -0.5))
        save(sym, cv)

    # --- indices, FX, volatility
    cv = badge(hexc('#0b1f33'))
    for x, top, bot, c in ((4, 7, 11, GREEN), (6, 6, 10, GREEN), (8, 7, 10, RED), (10, 4, 8, GREEN), (12, 3, 6, GREEN)):
        for y in range(top, bot + 1):
            cv.px(x, y, c)
        cv.px(x, top - 1, shade(c, -0.4))
    save('SP500', cv)
    cv = badge(hexc('#111111'), hexc('#f2f2f2'))
    glyph(cv, FONT['X'], {'#': WHITE})
    save('XYZ100', cv)
    for sym, col, s in (('JP225', '#bc002d', 'JP'), ('KR200', '#003478', 'KR'), 
                        ('TLT', '#2a3f5f', 'T'), ('SMH', '#3b2a5f', 'S'), ('XLE', '#5f3b2a', 'E'),
                        ('EWJ', '#bc002d', 'J'), ('EWY', '#003478', 'K'), ('EWZ', '#009c3b', 'B'), ('EWT', '#fe0000', 'T')):
        cv = badge(hexc(col))
        letters(cv, s, WHITE, shade(hexc(col), -0.5))
        save(sym, cv)
    for sym, col, g in (('EUR', '#003399', ['..###', '.#...', '####.', '.#...', '####.', '.#...', '..###']),
                        ('JPY', '#bc002d', ['#...#', '.#.#.', '..#..', '#####', '..#..', '#####', '..#..']),
                        ('GBP', '#012169', ['..##.', '.#..#', '.#...', '###..', '.#...', '.#...', '#####'])):
        cv = badge(hexc(col))
        glyph(cv, g, {'#': hexc('#ffcc00') if sym == 'EUR' else WHITE}, shadow=shade(hexc(col), -0.5))
        save(sym, cv)

    # --- commodities: ingots, a barrel, a flame
    def ingot_logo(sym, base, hi, lo, bg):
        cv = badge(bg)
        for (x, y) in ((3, 9), (8, 9), (5, 5)):
            cv.rect(x + 1, y, x + 4, y, hi)
            cv.rect(x, y + 1, x + 5, y + 2, base)
            cv.rect(x, y + 3, x + 5, y + 3, lo)
        save(sym, cv)

    ingot_logo('GOLD', GOLD, GOLD_HI, GOLD_LO, hexc('#14213d'))
    ingot_logo('SILVER', hexc('#c9d1d6'), WHITE, hexc('#7d878d'), hexc('#14213d'))
    ingot_logo('COPPER', hexc('#d9844a'), hexc('#f5b98a'), hexc('#8a4a22'), hexc('#14213d'))
    ingot_logo('PLATINUM', hexc('#e5e4e2'), WHITE, hexc('#9a9a98'), hexc('#2a2a3d'))
    ingot_logo('PALLADIUM', hexc('#cfd6d9'), WHITE, hexc('#8d989c'), hexc('#3d2a2a'))
    ingot_logo('UGOLD', GOLD, GOLD_HI, GOLD_LO, hexc('#0a2a24'))
    ingot_logo('UCOPPER', hexc('#d9844a'), hexc('#f5b98a'), hexc('#8a4a22'), hexc('#0a2a24'))
    for sym, bg in (('CL', '#f2c14e'), ('BRENTOIL', '#e0a040'), ('HO', '#c08030')):
        cv = badge(hexc(bg))
        glyph(cv, ['.#####.', '#######', '.#####.', '#######', '.#####.', '#######', '.#####.'],
              {'#': hexc('#1a1a1a')})
        cv.rect(5, 7, 9, 7, hexc(bg))
        save(sym, cv)
    cv = badge(hexc('#0b1f33'))
    glyph(cv, ['...#...', '..##...', '..###..', '.#####.', '.##B##.', '##BBB##', '.#BBB#.', '..###..'],
          {'#': hexc('#3d7cff'), 'B': hexc('#9fd0ff')})
    save('NATGAS', cv)
    # the blank badge other markets are drawn on: light grey so a tint keeps its shading
    cv = badge(hexc('#d8d8d8'))
    cv.save('logo/_badge.png')
    return L


def sheet(path, imgs):
    cols = 12
    n = len(imgs)
    rows = (n + cols - 1) // cols
    out = Image.new('RGBA', (cols * 72, rows * 72), (30, 30, 34, 255))
    for i, im in enumerate(imgs):
        big = im.resize((64, 64), Image.NEAREST)
        out.paste(big, ((i % cols) * 72 + 4, (i // cols) * 72 + 4), big)
    out.save(path)


if __name__ == '__main__':
    b = blocks()
    it = items()
    lg = logos()
    print('blocks', len(b), 'items', len(it), 'logos', len(lg))
    if '--sheet' in sys.argv:
        p = sys.argv[sys.argv.index('--sheet') + 1]
        sheet(p, list(b.values()) + list(it.values()) + list(lg.values()))
        with open(os.path.join(ROOT, '..', 'logos.txt'), 'w') as f:
            f.write('\n'.join(sorted(lg)))
