/* Halation: bright areas bleed a tinted glow. Owns the highlight threshold and the energy-map
 * resolution (long side THUMB_LONG cells); the film only supplies strength, radius (in map cells) and tint.
 * Pipeline: gather highlights -> average down to a map whose long side is THUMB_LONG cells and whose
 * short side keeps the image aspect (D1: square cells, so the glow is round) -> blur -> add back,
 * bilinearly upscaled, tinted and scaled by strength, saturating at 255. Works for any image size >= 1x1;
 * the 8x8 cell is the 768x768 case. */
#include "halation.h"
#include "pixel.h"

#define THUMB_LONG 96u /* map cells along the image's long side */
/* Engine-wide, not per film: only luma at or above this takes part (>= 204, i.e. 0.8 of full scale).
 * A highlight's energy ramps from 0 at the threshold to 255 at white, so the glow has no hard edge. */
#define HIGHLIGHT_THRESHOLD 204u

/* The energy map: w x h cells, w * h <= THUMB_LONG^2. Cells are square: the long side has THUMB_LONG
 * cells, the short side round(THUMB_LONG * short / long), at least 1 (D1). */
typedef struct {
    uint32_t w, h;
} thumb_dims_t;
static thumb_dims_t thumb_dims(const fe_image_t *image);

/* Bilinear sampling of an `cells`-wide map along one image axis of n pixels: sample i sits at map
 * coordinate (i + 0.5) * cells / n - 0.5, kept in Q8 and clamped to the map. Stepped from one pixel
 * to the next without a division per pixel. */
typedef struct {
    uint32_t n;      /* image pixels along the axis */
    uint32_t cells;  /* map cells along the axis */
    uint32_t q, r;   /* quotient and remainder of ((2i + 1) * cells * 128) / n */
    uint32_t dq, dr; /* the same for the step 2 * cells * 128 */
} axis_t;
static void axis_init(axis_t *axis, uint32_t n, uint32_t cells);
static void axis_next(axis_t *axis);
/* Map cells i0 and i1 (= i0 + 1, or i0 at the far edge) and the weight of i1 in 0..255, for the current pixel. */
static void axis_sample(const axis_t *axis, uint32_t *i0, uint32_t *i1, uint32_t *frac);

/* Average image down to the energy map (dims.w x dims.h); pixels below the threshold count as 0. */
static void gather_highlights(const fe_image_t *image, thumb_dims_t dims, uint8_t *thumb);

/* One box-blur pass: `lines` lines of `len` cells; element i of line l is src[l * line_step + i * elem_step]. */
static void blur_pass(const uint8_t *src, uint8_t *dst, size_t elem_step, size_t line_step, uint32_t lines,
                      uint32_t len, unsigned radius);

/* Box blur of the map, radius 0..255 in map cells (the window is clipped to the map, light that
 * leaves the map is lost); scratch is another dims.w * dims.h bytes. */
static void blur_thumb(uint8_t *thumb, uint8_t *scratch, thumb_dims_t dims, unsigned radius);

/* Add tint * glow * strength to every pixel (map sampled bilinearly), saturating. */
static void composite_glow(fe_image_t *image, const uint8_t *thumb, thumb_dims_t dims, const fe_film_t *film);

static thumb_dims_t thumb_dims(const fe_image_t *image)
{
    const uint32_t w = image->width, h = image->height;
    const uint32_t lng = w > h ? w : h, sht = w > h ? h : w;
    uint32_t cells = (THUMB_LONG * sht + lng / 2u) / lng; /* round(THUMB_LONG * short / long) */
    cells = cells < 1u ? 1u : cells;
    thumb_dims_t dims = {w >= h ? THUMB_LONG : cells, w >= h ? cells : THUMB_LONG};
    return dims;
}

static void axis_init(axis_t *axis, uint32_t n, uint32_t cells)
{
    const uint32_t start = cells * 128u, step = 2u * cells * 128u;
    axis->n = n;
    axis->cells = cells;
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
    const uint32_t last = (axis->cells - 1u) * 256u;
    uint32_t pos = axis->q > 128u ? axis->q - 128u : 0u;
    if (pos > last) {
        pos = last;
    }
    *i0 = pos >> 8;
    *i1 = *i0 + 1u < axis->cells ? *i0 + 1u : *i0;
    *frac = pos & 255u;
}

static void gather_highlights(const fe_image_t *image, thumb_dims_t dims, uint8_t *thumb)
{
    const uint32_t w = image->width, h = image->height;
    /* Cell columns [xlo, xhi): width / dims.w pixels, at least one (so images narrower than the map work). */
    uint16_t xlo[THUMB_LONG], xhi[THUMB_LONG];
    for (uint32_t cx = 0; cx < dims.w; cx++) {
        const uint32_t lo = cx * w / dims.w, hi = (cx + 1u) * w / dims.w;
        xlo[cx] = (uint16_t)lo;
        xhi[cx] = (uint16_t)(hi > lo ? hi : lo + 1u);
    }
    const unsigned step = fe_pixel_step(image);
    for (uint32_t cy = 0; cy < dims.h; cy++) {
        const uint32_t ylo = cy * h / dims.h;
        uint32_t yhi = (cy + 1u) * h / dims.h;
        if (yhi <= ylo) {
            yhi = ylo + 1u;
        }
        uint32_t energy[THUMB_LONG] = {0};
        for (uint32_t y = ylo; y < yhi; y++) {
            for (uint32_t cx = 0; cx < dims.w; cx++) {
                uint32_t sum = 0;
                const uint8_t *px = fe_pixel(image, xlo[cx], y);
                for (uint32_t x = xlo[cx]; x < xhi[cx]; x++, px += step) {
                    const unsigned luma = fe_luma(px[0], px[1], px[2]);
                    if (luma >= HIGHLIGHT_THRESHOLD) {
                        sum += (luma - HIGHLIGHT_THRESHOLD) * 255u / (255u - HIGHLIGHT_THRESHOLD);
                    }
                }
                energy[cx] += sum;
            }
        }
        for (uint32_t cx = 0; cx < dims.w; cx++) {
            thumb[cy * dims.w + cx] = (uint8_t)(energy[cx] / ((uint32_t)(xhi[cx] - xlo[cx]) * (yhi - ylo)));
        }
    }
}

static void blur_pass(const uint8_t *src, uint8_t *dst, size_t elem_step, size_t line_step, uint32_t lines,
                      uint32_t len, unsigned radius)
{
    const unsigned width = 2u * radius + 1u;
    for (size_t line = 0; line < lines; line++) {
        const uint8_t *in = src + line * line_step;
        uint8_t *out = dst + line * line_step;
        unsigned prefix[THUMB_LONG + 1u];
        prefix[0] = 0;
        for (unsigned i = 0; i < len; i++) {
            prefix[i + 1u] = prefix[i] + in[i * elem_step];
        }
        for (unsigned i = 0; i < len; i++) {
            const unsigned lo = i > radius ? i - radius : 0u;
            const unsigned hi = i + radius < len - 1u ? i + radius : len - 1u;
            out[i * elem_step] = (uint8_t)((prefix[hi + 1u] - prefix[lo] + width / 2u) / width);
        }
    }
}

static void blur_thumb(uint8_t *thumb, uint8_t *scratch, thumb_dims_t dims, unsigned radius)
{
    blur_pass(thumb, scratch, 1u, dims.w, dims.h, dims.w, radius);   /* along rows */
    blur_pass(scratch, thumb, dims.w, 1u, dims.w, dims.h, radius);   /* along columns */
}

static void composite_glow(fe_image_t *image, const uint8_t *thumb, thumb_dims_t dims, const fe_film_t *film)
{
    /* Per channel gain, Q16: strength (Q8, 0..256) * tint (0..255). */
    const uint32_t strength = (uint32_t)(film->halation_strength * 256.0f + 0.5f);
    uint32_t gain[3];
    for (int c = 0; c < 3; c++) {
        gain[c] = strength * film->halation_tint[c];
    }
    const unsigned step = fe_pixel_step(image);
    uint8_t line[THUMB_LONG]; /* the map interpolated vertically for the current image row */
    axis_t ay;
    axis_init(&ay, image->height, dims.h);
    for (uint32_t y = 0; y < image->height; y++, axis_next(&ay)) {
        uint32_t y0, y1, fy;
        axis_sample(&ay, &y0, &y1, &fy);
        for (uint32_t i = 0; i < dims.w; i++) {
            line[i] = (uint8_t)((thumb[y0 * dims.w + i] * (256u - fy) + thumb[y1 * dims.w + i] * fy + 128u) >> 8);
        }
        uint8_t *px = fe_pixel(image, 0, y);
        axis_t ax;
        axis_init(&ax, image->width, dims.w);
        for (uint32_t x = 0; x < image->width; x++, axis_next(&ax), px += step) {
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
    const thumb_dims_t dims = thumb_dims(image);
    const size_t cells = (size_t)dims.w * dims.h;
    uint8_t *buffers = alloc->alloc(2u * cells);
    if (buffers == NULL) {
        return FE_ERR_NO_MEM;
    }
    uint8_t *thumb = buffers, *scratch = buffers + cells;
    gather_highlights(image, dims, thumb);
    blur_thumb(thumb, scratch, dims, film->halation_radius);
    composite_glow(image, thumb, dims, film);
    alloc->release(buffers);
    return FE_OK;
}
