/* Halation: bright areas bleed a tinted glow. Owns the highlight threshold and the 96x96 working
 * resolution; the film only supplies strength, radius (in 96x96 pixels) and tint.
 * Pipeline: gather highlights -> average down to 96x96 -> blur -> add back, bilinearly upscaled,
 * tinted and scaled by strength, saturating at 255. Works for any image size >= 1x1; the spec's 8x8
 * cell is the 768x768 case of "cell = image / 96". */
#include "halation.h"
#include "pixel.h"

#define THUMB_DIM 96u
/* Engine-wide, not per film: only luma at or above this takes part (>= 191, i.e. 0.75 of full scale).
 * A highlight's energy ramps from 0 at the threshold to 255 at white, so the glow has no hard edge. */
#define HIGHLIGHT_THRESHOLD 204u

/* Bilinear sampling of the THUMB_DIM-wide map along one image axis of n pixels: sample i sits at
 * thumb coordinate (i + 0.5) * THUMB_DIM / n - 0.5, kept in Q8 and clamped to the map. Stepped from
 * one pixel to the next without a division per pixel. */
typedef struct {
    uint32_t n;      /* image pixels along the axis */
    uint32_t q, r;   /* quotient and remainder of ((2i + 1) * THUMB_DIM * 128) / n */
    uint32_t dq, dr; /* the same for the step 2 * THUMB_DIM * 128 */
} axis_t;
static void axis_init(axis_t *axis, uint32_t n);
static void axis_next(axis_t *axis);
/* Thumb cells i0 and i1 (= i0 + 1, or i0 at the far edge) and the weight of i1 in 0..255, for the current pixel. */
static void axis_sample(const axis_t *axis, uint32_t *i0, uint32_t *i1, uint32_t *frac);

/* Average image down to a THUMB_DIM x THUMB_DIM energy map; pixels below the threshold count as 0. */
static void gather_highlights(const fe_image_t *image, uint8_t *thumb);

/* Box blur of the map, radius 0..255 in map pixels (the window is clipped to the map, light that
 * leaves the map is lost); scratch is another THUMB_DIM^2 bytes. */
static void blur_thumb(uint8_t *thumb, uint8_t *scratch, unsigned radius);

/* Add tint * glow * strength to every pixel (map sampled bilinearly), saturating. */
static void composite_glow(fe_image_t *image, const uint8_t *thumb, const fe_film_t *film);

static void axis_init(axis_t *axis, uint32_t n)
{
    const uint32_t start = THUMB_DIM * 128u, step = 2u * THUMB_DIM * 128u;
    axis->n = n;
    axis->q = start / n;
    axis->r = start % n;
    axis->dq = step / n;
    axis->dr = step % n;
}

static void axis_next(axis_t *axis)
{
    axis->q += axis->dq;
    axis->r += axis->dr;
    if (axis->r >= axis->n) {
        axis->r -= axis->n;
        axis->q++;
    }
}

static void axis_sample(const axis_t *axis, uint32_t *i0, uint32_t *i1, uint32_t *frac)
{
    const uint32_t last = (THUMB_DIM - 1u) * 256u;
    uint32_t pos = axis->q > 128u ? axis->q - 128u : 0u;
    if (pos > last) {
        pos = last;
    }
    *i0 = pos >> 8;
    *i1 = *i0 + 1u < THUMB_DIM ? *i0 + 1u : *i0;
    *frac = pos & 255u;
}

static void gather_highlights(const fe_image_t *image, uint8_t *thumb)
{
    const uint32_t w = image->width, h = image->height;
    /* Cell columns [xlo, xhi): width / 96 pixels, at least one (so images narrower than 96 work). */
    uint16_t xlo[THUMB_DIM], xhi[THUMB_DIM];
    for (uint32_t cx = 0; cx < THUMB_DIM; cx++) {
        const uint32_t lo = cx * w / THUMB_DIM, hi = (cx + 1u) * w / THUMB_DIM;
        xlo[cx] = (uint16_t)lo;
        xhi[cx] = (uint16_t)(hi > lo ? hi : lo + 1u);
    }
    for (uint32_t cy = 0; cy < THUMB_DIM; cy++) {
        const uint32_t ylo = cy * h / THUMB_DIM;
        uint32_t yhi = (cy + 1u) * h / THUMB_DIM;
        if (yhi <= ylo) {
            yhi = ylo + 1u;
        }
        uint32_t energy[THUMB_DIM] = {0};
        for (uint32_t y = ylo; y < yhi; y++) {
            const uint8_t *row = image->rgb + (size_t)y * image->stride;
            for (uint32_t cx = 0; cx < THUMB_DIM; cx++) {
                uint32_t sum = 0;
                for (uint32_t x = xlo[cx]; x < xhi[cx]; x++) {
                    const uint8_t *px = row + 3u * x;
                    const unsigned luma = fe_luma(px[0], px[1], px[2]);
                    if (luma >= HIGHLIGHT_THRESHOLD) {
                        sum += (luma - HIGHLIGHT_THRESHOLD) * 255u / (255u - HIGHLIGHT_THRESHOLD);
                    }
                }
                energy[cx] += sum;
            }
        }
        for (uint32_t cx = 0; cx < THUMB_DIM; cx++) {
            thumb[cy * THUMB_DIM + cx] = (uint8_t)(energy[cx] / ((uint32_t)(xhi[cx] - xlo[cx]) * (yhi - ylo)));
        }
    }
}

/* One box-blur pass over all THUMB_DIM lines of the map. Element i of line l is src[l * line_step + i * elem_step]. */
static void blur_pass(const uint8_t *src, uint8_t *dst, size_t elem_step, size_t line_step, unsigned radius)
{
    const unsigned width = 2u * radius + 1u;
    for (size_t line = 0; line < THUMB_DIM; line++) {
        const uint8_t *in = src + line * line_step;
        uint8_t *out = dst + line * line_step;
        unsigned prefix[THUMB_DIM + 1u];
        prefix[0] = 0;
        for (unsigned i = 0; i < THUMB_DIM; i++) {
            prefix[i + 1u] = prefix[i] + in[i * elem_step];
        }
        for (unsigned i = 0; i < THUMB_DIM; i++) {
            const unsigned lo = i > radius ? i - radius : 0u;
            const unsigned hi = i + radius < THUMB_DIM - 1u ? i + radius : THUMB_DIM - 1u;
            out[i * elem_step] = (uint8_t)((prefix[hi + 1u] - prefix[lo] + width / 2u) / width);
        }
    }
}

static void blur_thumb(uint8_t *thumb, uint8_t *scratch, unsigned radius)
{
    blur_pass(thumb, scratch, 1u, THUMB_DIM, radius);   /* along rows */
    blur_pass(scratch, thumb, THUMB_DIM, 1u, radius);   /* along columns */
}

static void composite_glow(fe_image_t *image, const uint8_t *thumb, const fe_film_t *film)
{
    /* Per channel gain, Q16: strength (Q8, 0..256) * tint (0..255). */
    const uint32_t strength = (uint32_t)(film->halation_strength * 256.0f + 0.5f);
    uint32_t gain[3];
    for (int c = 0; c < 3; c++) {
        gain[c] = strength * film->halation_tint[c];
    }
    uint8_t line[THUMB_DIM]; /* the map interpolated vertically for the current image row */
    axis_t ay;
    axis_init(&ay, image->height);
    for (uint32_t y = 0; y < image->height; y++, axis_next(&ay)) {
        uint32_t y0, y1, fy;
        axis_sample(&ay, &y0, &y1, &fy);
        for (uint32_t i = 0; i < THUMB_DIM; i++) {
            line[i] = (uint8_t)((thumb[y0 * THUMB_DIM + i] * (256u - fy) + thumb[y1 * THUMB_DIM + i] * fy + 128u) >> 8);
        }
        uint8_t *px = image->rgb + (size_t)y * image->stride;
        axis_t ax;
        axis_init(&ax, image->width);
        for (uint32_t x = 0; x < image->width; x++, axis_next(&ax), px += 3) {
            uint32_t x0, x1, fx;
            axis_sample(&ax, &x0, &x1, &fx);
            const uint32_t glow = (line[x0] * (256u - fx) + line[x1] * fx + 128u) >> 8;
            if (glow == 0) {
                continue;
            }
            for (int c = 0; c < 3; c++) {
                px[c] = fe_clamp_u8((int)px[c] + (int)((glow * gain[c] + 32768u) >> 16));
            }
        }
    }
}

fe_err_t fe_halation_apply(const fe_film_t *film, fe_image_t *image, const fe_allocator_t *alloc)
{
    if (!(film->halation_strength > 0.0f)) {
        return FE_OK;
    }
    uint8_t *buffers = alloc->alloc(2u * THUMB_DIM * THUMB_DIM);
    if (buffers == NULL) {
        return FE_ERR_NO_MEM;
    }
    uint8_t *thumb = buffers, *scratch = buffers + THUMB_DIM * THUMB_DIM;
    gather_highlights(image, thumb);
    blur_thumb(thumb, scratch, film->halation_radius);
    composite_glow(image, thumb, film);
    alloc->release(buffers);
    return FE_OK;
}
