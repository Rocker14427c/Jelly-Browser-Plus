#!/usr/bin/env python3
"""
Generates the animated "jelly" wordmark used at the top of the README:
a wobbly jelly blob next to a bouncing "Jelly Browser+" title.

Output: ../assets/jelly-title.gif

Requires: Pillow, a bold TTF font (DejaVu Sans Bold is used by default).

Tweak the constants at the top and re-run to regenerate the banner.
"""
import math
import os
import random
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ----------------------------------------------------------------------------
# Config
# ----------------------------------------------------------------------------
W, H = 600, 140
FRAMES = 20
DURATION_MS = 90  # per frame
LOOP_FOREVER = 0

OUT_PATH = os.path.join(os.path.dirname(__file__), "..", "assets", "jelly-title.gif")

FONT_PATH = os.environ.get(
    "JELLY_FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
)

# Palette
BG_TOP = (36, 26, 78)          # deep indigo
BG_BOTTOM = (16, 13, 36)       # near-black purple
GLOW = (130, 100, 255)         # soft purple glow behind the blob
TEXT_MAIN = (245, 247, 255)    # near-white
TEXT_ACCENT = (216, 201, 255)  # lavender for "Jelly"
SHADOW = (0, 0, 0)

BLOB_BODY = (155, 123, 255)
BLOB_LIGHT = (198, 179, 255)
BLOB_RIM = (228, 220, 255)
BLOB_DARK = (36, 26, 78)

# ----------------------------------------------------------------------------
# Geometry
# ----------------------------------------------------------------------------
BLOB_R = 48
GAP = 26
MARGIN = 16
TEXT_BASE = 110
FONT_TARGET_PX = 46   # auto-shrunk until the whole wordmark fits
RENDER_SCALE = 3      # text is rendered 3x, then scaled down (crisper edges)


def lerp_color(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def make_background(cx, cy):
    bg = Image.new("RGB", (W, H))
    px = bg.load()
    rng = random.Random(7)  # deterministic: identical every frame, GIF-friendly
    for y in range(H):
        t = y / (H - 1)
        row = lerp_color(BG_TOP, BG_BOTTOM, t)
        for x in range(W):
            n = rng.randint(-3, 3)  # static noise hides gradient banding w/o dithering
            px[x, y] = tuple(max(0, min(255, c + n)) for c in row)
    # soft radial glow behind the blob area
    glow = Image.new("L", (W, H), 0)
    gd = ImageDraw.Draw(glow)
    for r, a in ((130, 30), (95, 55), (65, 85)):
        gd.ellipse((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8), fill=a)
    glow = glow.filter(ImageFilter.GaussianBlur(40))
    glow_rgb = Image.new("RGB", (W, H), GLOW)
    return Image.composite(glow_rgb, bg, glow)


def text_width_px(font_px):
    font = ImageFont.truetype(FONT_PATH, font_px)
    probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    return probe.textlength("Jelly Browser+", font=font)


def layout():
    """Returns (blob_cx, blob_cy, text_x, font_px) so the wordmark is centered."""
    font_px = FONT_TARGET_PX
    while font_px > 32:
        w = text_width_px(font_px)
        if 2 * BLOB_R + GAP + w <= W - 2 * MARGIN:
            break
        font_px -= 2
    total = 2 * BLOB_R + GAP + w
    start = (W - total) / 2
    cx = start + BLOB_R
    text_x = start + 2 * BLOB_R + GAP
    cy = TEXT_BASE - 40
    return cx, cy, text_x, font_px


def text_layers(font_px):
    """Pre-render the two text chunks at RENDER_SCALE x."""
    font = ImageFont.truetype(FONT_PATH, font_px * RENDER_SCALE)
    pad = 12 * RENDER_SCALE
    probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))

    def render(chunk, color):
        w = probe.textlength(chunk, font=font)
        img = Image.new(
            "RGBA", (int(w) + pad * 2, font_px * RENDER_SCALE + pad * 2), (0, 0, 0, 0)
        )
        d = ImageDraw.Draw(img)
        d.text((pad, pad), chunk, font=font, fill=color)
        return img

    jelly = render("Jelly", TEXT_ACCENT)
    rest = render(" Browser+", TEXT_MAIN)
    tw = jelly.size[0] + rest.size[0]
    th = max(jelly.size[1], rest.size[1])
    text_img = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    text_img.alpha_composite(jelly, (0, th - jelly.size[1]))
    text_img.alpha_composite(rest, (jelly.size[0], th - rest.size[1]))
    # downscale from RENDER_SCALE x to 1x (extra AA levels for crisp edges)
    text_img = text_img.resize(
        (max(1, tw // RENDER_SCALE), max(1, th // RENDER_SCALE)), Image.LANCZOS
    )
    return text_img


def scale_rotate(img, sx, sy, angle_deg):
    w, h = img.size
    nw, nh = max(1, int(w * sx)), max(1, int(h * sy))
    if sx != 1.0 or sy != 1.0:
        img = img.transform(
            (nw, nh), Image.AFFINE, (1.0 / sx, 0, 0, 0, 1.0 / sy, 0),
            resample=Image.BICUBIC,
        )
    if angle_deg:
        img = img.rotate(
            angle_deg, resample=Image.BICUBIC, expand=False, fillcolor=(0, 0, 0, 0)
        )
    return img


def shadow_of(img):
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sh.paste(SHADOW + (255,), (0, 0), img)
    return sh.filter(ImageFilter.GaussianBlur(5))


def blob_polygon(cx, cy, r, sx, sy, phase):
    """Jelly dome polygon points for the current wobble frame."""
    rx, ry = r * sx, r * sy
    pts = []
    steps = 48
    for i in range(steps + 1):
        t = -1.0 + 2.0 * i / steps  # -1..1
        pts.append((cx + t * rx, cy - ry * math.sqrt(max(0.0, 1.0 - t * t))))
    bottom = cy + 24 * sy
    k = 5
    right_pts = []
    for i in range(k, -1, -1):
        t = -1.0 + 2.0 * i / k
        x = cx + t * rx
        y = bottom + 6 * math.sin(phase * 2.2 + i * 1.25)
        right_pts.append((x, y))
        if i > 0:
            tm = -1.0 + 2.0 * (i - 0.5) / k
            right_pts.append(
                (cx + tm * rx, bottom + 5 + 6 * math.sin(phase * 2.2 + (i - 0.5) * 1.25))
            )
    pts += right_pts
    return pts


def draw_blob(canvas, cx, cy, r, sx, sy, phase, bob):
    # ground shadow (widens slightly when squashed)
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sw = r * sx * 1.15 * (1 + 0.35 * math.sin(phase))
    sd.ellipse(
        (cx - sw, cy + r * 0.62 + bob * 0.4, cx + sw, cy + r * 0.62 + 14),
        fill=(0, 0, 0, 90),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(6))
    canvas.alpha_composite(shadow)

    pts = blob_polygon(cx, cy, r, sx, sy, phase)
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)

    # body + lighter upper region, clipped to the blob shape
    body = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.polygon(pts, fill=BLOB_BODY + (255,))
    top = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(top)
    td.ellipse(
        (cx - r * sx * 0.95, cy - r * sy * 1.1, cx + r * sx * 0.95, cy - r * sy * 0.15),
        fill=BLOB_LIGHT + (150,),
    )
    body.alpha_composite(top)
    canvas.alpha_composite(Image.composite(body, Image.new("RGBA", canvas.size, (0, 0, 0, 0)), mask))

    # rim light along the top edge
    rim = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rd.ellipse(
        (cx - r * sx * 0.88, cy - r * sy * 0.92, cx + r * sx * 0.88, cy - r * sy * 0.5),
        outline=BLOB_RIM + (110,), width=3,
    )
    canvas.alpha_composite(rim)

    # glossy highlight
    hl = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.ellipse(
        (cx - r * sx * 0.45, cy - r * sy * 0.75, cx - r * sx * 0.05, cy - r * sy * 0.35),
        fill=(255, 255, 255, 120),
    )
    hd.ellipse(
        (cx - r * sx * 0.30, cy - r * sy * 0.60, cx - r * sx * 0.16, cy - r * sy * 0.46),
        fill=(255, 255, 255, 150),
    )
    canvas.alpha_composite(hl)

    # face
    face = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    fd = ImageDraw.Draw(face)
    eye_r = 4.2 * sx
    fd.ellipse(
        (cx - 19 * sx - eye_r, cy - 11 * sy - eye_r, cx - 19 * sx + eye_r, cy - 11 * sy + eye_r),
        fill=BLOB_DARK + (235,),
    )
    fd.ellipse(
        (cx + 19 * sx - eye_r, cy - 11 * sy - eye_r, cx + 19 * sx + eye_r, cy - 11 * sy + eye_r),
        fill=BLOB_DARK + (235,),
    )
    fd.arc(
        (cx - 14 * sx, cy + 2 * sy, cx + 14 * sx, cy + 2 * sy + 18 * sy),
        start=20, end=160, fill=BLOB_DARK + (235,), width=3,
    )
    canvas.alpha_composite(face)


def main():
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)

    cx, cy, text_x, font_px = layout()
    text_img = text_layers(font_px)
    pad = 12  # 1x padding baked into the text image
    bg = make_background(cx, cy)

    frames = []
    for i in range(FRAMES):
        phase = 2 * math.pi * i / FRAMES

        # text wobble: squash & stretch, slightly out of sync with the blob
        tx_sx = 1.0 - 0.045 * math.sin(phase)
        tx_sy = 1.0 + 0.060 * math.sin(phase)
        angle = 1.0 * math.sin(phase + 0.5)

        canvas = bg.copy().convert("RGBA")

        # blob wobble (a touch later than the text, like they share a jelly surface)
        blob_phase = phase - 0.7
        b_sx = 1.0 - 0.070 * math.sin(blob_phase)
        b_sy = 1.0 + 0.095 * math.sin(blob_phase)
        bob = 2.5 * math.sin(blob_phase + 1.1)
        draw_blob(canvas, cx, cy + bob, BLOB_R, b_sx, b_sy, blob_phase, bob)

        # text + shadow, bottom-anchored so it squashes into the ground
        t_frame = scale_rotate(text_img, tx_sx, tx_sy, angle)
        sh_frame = scale_rotate(shadow_of(text_img), tx_sx, tx_sy, angle)
        ty = int(TEXT_BASE - t_frame.size[1] + pad * tx_sy - 2)
        canvas.alpha_composite(sh_frame, (int(text_x) + 4, ty + int(6 * (1 + 0.6 * (1 - tx_sy)))))
        canvas.alpha_composite(t_frame, (int(text_x), ty))

        frames.append(canvas.convert("RGB"))

    # Quantize all frames to one shared palette (no dithering — the static
    # background noise and 3x-rendered text already avoid banding/jaggies,
    # and dithered noise would defeat GIF frame compression).
    base = frames[0].quantize(colors=256, method=Image.Quantize.MEDIANCUT)
    gif_frames = [base] + [f.quantize(palette=base) for f in frames[1:]]
    gif_frames[0].save(
        OUT_PATH,
        save_all=True,
        append_images=gif_frames[1:],
        duration=DURATION_MS,
        loop=LOOP_FOREVER,
        disposal=2,
        optimize=False,
    )

    # Post-process with gifsicle when available: frame diffing typically
    # shrinks the file 2-3x (the static background is stored only once).
    if shutil.which("gifsicle"):
        tmp = OUT_PATH + ".tmp.gif"
        subprocess.run(
            ["gifsicle", "-O3", "--colors=256", OUT_PATH, "-o", tmp], check=True
        )
        os.replace(tmp, OUT_PATH)

    print(f"wrote {OUT_PATH} ({os.path.getsize(OUT_PATH) / 1024:.0f} KiB)")


if __name__ == "__main__":
    main()
