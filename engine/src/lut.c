/* 3D LUT application. Tetrahedral interpolation lives here and only here: the preview table and
 * develop both go through fe_lut_apply_pixel, so they cannot disagree. fe_lut_apply_image sits in the
 * same translation unit so the per-pixel call can be inlined into its loop. */
#include "lut.h"

/* Exact: weights are the fractions of 255 (in 0..255), the sum is rounded once at the end. */
void fe_lut_apply_pixel(const fe_film_t *film, const uint8_t in[3], uint8_t out[3])
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
    for (uint16_t y = 0; y < image->height; y++) {
        uint8_t *row = image->rgb + (size_t)y * image->stride;
        for (uint16_t x = 0; x < image->width; x++) {
            fe_lut_apply_pixel(film, row + 3u * x, row + 3u * x);
        }
    }
}
