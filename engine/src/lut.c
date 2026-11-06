/* 3D LUT application. Tetrahedral interpolation lives here and only here; develop goes through it. */
#include "lut.h"
#include "pixel.h"

/* Exact: weights are the fractions of 255 (in 0..255), the sum is rounded once at the end. */
/* One RGB888 pixel through the LUT. in and out may alias. */
static void lut_pixel(const fe_film_t *film, const uint8_t in[3], uint8_t out[3])
{
    const unsigned n = film->lut_size;
    const unsigned step[3] = {3u, 3u * n, 3u * n * n}; /* bytes between neighbouring nodes along r, g, b */
    unsigned base = 0;                                 /* byte offset of the cell's lowest corner */
    unsigned w[3];                                     /* position inside the cell along r, g, b, 0..255 */
    for (int ch = 0; ch < 3; ch++) {
        const unsigned pos = in[ch] * (n - 1u);
        unsigned node = pos / 255u;
        w[ch] = pos % 255u;
        if (node == n - 1u) { /* in == 255: the last node, as the far corner of the last cell */
            node--;
            w[ch] = 255u;
        }
        base += node * step[ch];
    }
    /* Tetrahedral: walk from the lowest corner to the opposite one along the axes, largest weight first. */
    unsigned a = 0, b = 1, c = 2, t;
    if (w[a] < w[b]) { t = a; a = b; b = t; }
    if (w[b] < w[c]) { t = b; b = c; c = t; }
    if (w[a] < w[b]) { t = a; a = b; b = t; }
    const unsigned p1 = step[a], p2 = p1 + step[b], p3 = p2 + step[c];
    for (unsigned k = 0; k < 3; k++) {
        const uint8_t *node = film->lut + base + k;
        const int c0 = node[0], c1 = node[p1], c2 = node[p2], c3 = node[p3];
        const int sum = c0 * 255 + (int)w[a] * (c1 - c0) + (int)w[b] * (c2 - c1) + (int)w[c] * (c3 - c2);
        out[k] = (uint8_t)((sum + 127) / 255);
    }
}

void fe_lut_apply_image(const fe_film_t *film, fe_image_t *image)
{
    const unsigned step = fe_pixel_step(image);
    for (uint32_t y = 0; y < image->height; y++) {
        uint8_t *px = fe_row(image, y);
        for (uint32_t x = 0; x < image->width; x++, px += step) {
            lut_pixel(film, px, px);
        }
    }
}
