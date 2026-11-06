/* Date stamp: "'YY MM DD" in orange seven-segment digits, procedurally drawn (no font).
 * Owns the stamp's layout (size from the short side, margin, position bottom-right), the digit shapes
 * and the orange. The rest of the image is never written. */
#include "date_stamp.h"
#include "pixel.h"

/* Layout: digit height = min(width, height) / STAMP_HEIGHT_DIV (D4: the same size in landscape and portrait); the stamp is 9 cells wide (' Y Y _ M M _ D D),
 * its right edge and its baseline are one digit height from the image edges. */
#define STAMP_HEIGHT_DIV 24
#define STAMP_TEXT_LEN 9

static const uint8_t ORANGE[3] = {255, 128, 16};

/* Segments of a digit, bit set = lit: a top, b upper right, c lower right, d bottom, e lower left,
 * f upper left, g middle. */
enum { SEG_A = 1, SEG_B = 2, SEG_C = 4, SEG_D = 8, SEG_E = 16, SEG_F = 32, SEG_G = 64 };
static const uint8_t SEGMENTS[10] = {
    SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F,         /* 0 */
    SEG_B | SEG_C,                                         /* 1 */
    SEG_A | SEG_B | SEG_D | SEG_E | SEG_G,                 /* 2 */
    SEG_A | SEG_B | SEG_C | SEG_D | SEG_G,                 /* 3 */
    SEG_B | SEG_C | SEG_F | SEG_G,                         /* 4 */
    SEG_A | SEG_C | SEG_D | SEG_F | SEG_G,                 /* 5 */
    SEG_A | SEG_C | SEG_D | SEG_E | SEG_F | SEG_G,         /* 6 */
    SEG_A | SEG_B | SEG_C,                                 /* 7 */
    SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F | SEG_G, /* 8 */
    SEG_A | SEG_B | SEG_C | SEG_D | SEG_F | SEG_G,         /* 9 */
};

/* Digit width and stroke thickness for digit height h: the one place the glyph proportions live. */
static int digit_width(int h);
static int stroke(int h);

/* Writes "'YY MM DD" (STAMP_TEXT_LEN chars + NUL) for date; every field is shown modulo 100. */
static void format_date(const fe_date_t *date, char out[STAMP_TEXT_LEN + 1]);

/* Paint the rectangle [x0,x1) x [y0,y1) orange, clipped to the image. */
static void fill_rect(fe_image_t *image, int x0, int y0, int x1, int y1);

/* Draw one glyph (digit, apostrophe or space) with its top-left at (x, y), digit height h. */
static void draw_glyph(fe_image_t *image, int x, int y, int h, char ch);

static int digit_width(int h)
{
    return h * 5 / 9;
}

static int stroke(int h)
{
    return h / 8 > 1 ? h / 8 : 1;
}

static void format_date(const fe_date_t *date, char out[STAMP_TEXT_LEN + 1])
{
    const unsigned fields[3] = {date->year % 100u, date->month % 100u, date->day % 100u};
    size_t n = 0;
    out[n++] = '\'';
    for (int f = 0; f < 3; f++) {
        out[n++] = (char)('0' + fields[f] / 10u);
        out[n++] = (char)('0' + fields[f] % 10u);
        if (f < 2) {
            out[n++] = ' ';
        }
    }
    out[n] = '\0';
}

static void fill_rect(fe_image_t *image, int x0, int y0, int x1, int y1)
{
    x0 = x0 < 0 ? 0 : x0;
    y0 = y0 < 0 ? 0 : y0;
    x1 = x1 > (int)image->width ? (int)image->width : x1;
    y1 = y1 > (int)image->height ? (int)image->height : y1;
    for (int y = y0; y < y1; y++) {
        uint8_t *px = fe_pixel(image, (uint32_t)x0, (uint32_t)y);
        for (int x = x0; x < x1; x++, px += fe_pixel_step(image)) {
            for (int c = 0; c < 3; c++) {
                px[c] = ORANGE[c];
            }
        }
    }
}

static void draw_glyph(fe_image_t *image, int x, int y, int h, char ch)
{
    const int w = digit_width(h), t = stroke(h), mid = h / 2;
    /* The glyph as rectangles {x0, y0, x1, y1} relative to its top-left, at most 7 (the digit 8). */
    int rects[7][4];
    int n = 0;
    if (ch == '\'') {
        const int r[4] = {w / 2 - t / 2, 0, w / 2 - t / 2 + t, h / 3};
        for (int i = 0; i < 4; i++) {
            rects[0][i] = r[i];
        }
        n = 1;
    } else if (ch >= '0' && ch <= '9') {
        const int segs = SEGMENTS[ch - '0'];
        const int all[7][4] = {
            {0, 0, w, t},                   /* a */
            {w - t, 0, w, mid + t / 2},     /* b */
            {w - t, mid - t / 2, w, h},     /* c */
            {0, h - t, w, h},               /* d */
            {0, mid - t / 2, t, h},         /* e */
            {0, 0, t, mid + t / 2},         /* f */
            {0, mid - t / 2, w, mid - t / 2 + t}, /* g */
        };
        for (int s = 0; s < 7; s++) {
            if (segs & (1 << s)) {
                for (int i = 0; i < 4; i++) {
                    rects[n][i] = all[s][i];
                }
                n++;
            }
        }
    }
    for (int i = 0; i < n; i++) {
        fill_rect(image, x + rects[i][0], y + rects[i][1], x + rects[i][2], y + rects[i][3]);
    }
}

void fe_date_stamp_apply(const fe_date_t *date, fe_image_t *image)
{
    char text[STAMP_TEXT_LEN + 1];
    format_date(date, text);
    const int short_side = image->width < image->height ? image->width : image->height;
    int h = short_side / STAMP_HEIGHT_DIV;
    h = h < 1 ? 1 : h;
    const int w = digit_width(h), gap = w / 2, advance = w + gap;
    const int text_width = STAMP_TEXT_LEN * advance - gap;
    const int x0 = (int)image->width - h - text_width;
    const int y0 = (int)image->height - h - h;
    for (int i = 0; i < STAMP_TEXT_LEN; i++) {
        draw_glyph(image, x0 + i * advance, y0, h, text[i]);
    }
}
