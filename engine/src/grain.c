/* Grain: monochrome noise on luma. Owns the grain texture (generated per call from film->grain_size;
 * the seed only picks where the tile starts) and the midtone weight (grain peaks in the midtones and
 * is weaker, not absent, toward black and white). The texture is zero mean, so grain does not shift
 * overall brightness. */
#include "grain.h"
#include "pixel.h"

#define GRAIN_TEX_DIM 1024u /* tileable texture, GRAIN_TEX_DIM^2 int16; a power of two so tiling is a mask */
/* D2: film->grain_size is tuned on a 768 px short side; it is scaled by min(width, height) / this. */
#define GRAIN_REF_SHORT_SIDE 768u
#define GRAIN_TEX_MASK (GRAIN_TEX_DIM - 1u)
#define GRAIN_TEX_PEAK 2047     /* texture values are clamped to +-GRAIN_TEX_PEAK */
#define GRAIN_TEX_MAD 400       /* mean absolute value the texture is scaled to (sigma ~ 500 for blurred noise) */
#define GRAIN_DELTA_AT_PEAK 56  /* levels added at texture peak, strength 1, midtone weight 1 (sigma ~ 14 levels) */
#define GRAIN_WEIGHT_FLOOR 32   /* midtone weight at luma 0 and 255, of 255 */

static uint32_t rng_next(uint32_t *state);

/* Fill tex (GRAIN_TEX_DIM^2) with tileable zero-mean noise whose blobs are ~size_px pixels wide
 * (size_px is already scaled to the image: grain_size * short side / GRAIN_REF_SHORT_SIDE); below 1 px
 * is a 1 px box. Always the same texture for the same size_px. */
static void make_texture(int16_t *tex, float size_px);

/* 0..255, peak at mid luma, GRAIN_WEIGHT_FLOOR at 0 and 255: Review Focus 2 needs grain to push pure
 * black and white against the clamp, so the weight must not reach 0. */
static unsigned midtone_weight(unsigned luma);

static uint32_t rng_next(uint32_t *state)
{
    uint32_t x = *state; /* xorshift32 */
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

/* Wrapped box blur of every line of the texture, in place: element i of line l is
 * tex[l * line_step + i * elem_step]; kernel is kern[0] + 2 * (kern[1..radius]), Q8. */
static void blur_lines(int16_t *tex, size_t elem_step, size_t line_step, const int *kern, int radius)
{
    int line[GRAIN_TEX_DIM];
    for (size_t l = 0; l < GRAIN_TEX_DIM; l++) {
        int16_t *base = tex + l * line_step;
        for (size_t i = 0; i < GRAIN_TEX_DIM; i++) {
            line[i] = base[i * elem_step];
        }
        for (size_t i = 0; i < GRAIN_TEX_DIM; i++) {
            int acc = kern[0] * line[i];
            for (int j = 1; j <= radius; j++) {
                acc += kern[j] * (line[(i + (size_t)j) & GRAIN_TEX_MASK] + line[(i - (size_t)j) & GRAIN_TEX_MASK]);
            }
            base[i * elem_step] = (int16_t)(acc / 256);
        }
    }
}

static void make_texture(int16_t *tex, float size_px)
{
    const size_t count = (size_t)GRAIN_TEX_DIM * GRAIN_TEX_DIM;
    uint32_t state = 0x9E3779B9u;
    for (size_t i = 0; i < count; i++) {
        tex[i] = (int16_t)((int)((rng_next(&state) >> 21) & 0x7FFu) - 1024);
    }
    /* Box of width W px: tap j covers the pixel [j - 0.5, j + 0.5] of the box [-W/2, W/2]. */
    const float width = size_px < 1.0f ? 1.0f : (size_px > 16.0f ? 16.0f : size_px); /* PHASE B: widen for the D2 scale */
    const int radius = (int)(width * 0.5f + 0.5f);
    float cover[9];
    float total = 0.0f;
    for (int j = 0; j <= radius; j++) {
        float c = width * 0.5f - (float)j + 0.5f;
        cover[j] = c < 0.0f ? 0.0f : (c > 1.0f ? 1.0f : c);
        total += j == 0 ? cover[j] : 2.0f * cover[j];
    }
    int kern[9];
    for (int j = 0; j <= radius; j++) {
        kern[j] = (int)(cover[j] / total * 256.0f + 0.5f);
    }
    blur_lines(tex, 1u, GRAIN_TEX_DIM, kern, radius);
    blur_lines(tex, GRAIN_TEX_DIM, 1u, kern, radius);
    /* Zero mean, then a fixed mean absolute value, so every film's grain has the same strength scale. */
    int32_t sum = 0;
    for (size_t i = 0; i < count; i++) {
        sum += tex[i];
    }
    const int mean = (int)(sum / (int32_t)count);
    int32_t abs_sum = 0;
    for (size_t i = 0; i < count; i++) {
        tex[i] = (int16_t)(tex[i] - mean);
        abs_sum += tex[i] < 0 ? -tex[i] : tex[i];
    }
    const int32_t mad = abs_sum / (int32_t)count > 0 ? abs_sum / (int32_t)count : 1;
    for (size_t i = 0; i < count; i++) {
        int32_t v = tex[i] * (int32_t)GRAIN_TEX_MAD / mad;
        tex[i] = (int16_t)(v < -GRAIN_TEX_PEAK ? -GRAIN_TEX_PEAK : (v > GRAIN_TEX_PEAK ? GRAIN_TEX_PEAK : v));
    }
}

static unsigned midtone_weight(unsigned luma)
{
    /* Parabola 4 l (255 - l) / 255^2, peak 1 at mid luma, lifted from the floor to 255. */
    const unsigned parabola = 4u * luma * (255u - luma); /* <= 65024 */
    return GRAIN_WEIGHT_FLOOR + parabola * (255u - GRAIN_WEIGHT_FLOOR) / 65025u;
}

fe_err_t fe_grain_apply(const fe_film_t *film, uint32_t seed, fe_image_t *image, const fe_allocator_t *alloc)
{
    if (!(film->grain_strength > 0.0f)) {
        return FE_OK;
    }
    int16_t *tex = alloc->alloc((size_t)GRAIN_TEX_DIM * GRAIN_TEX_DIM * sizeof(int16_t));
    if (tex == NULL) {
        return FE_ERR_NO_MEM;
    }
    const uint32_t short_side = image->width < image->height ? image->width : image->height;
    make_texture(tex, film->grain_size * (float)short_side / (float)GRAIN_REF_SHORT_SIDE);
    /* delta = texture * gain[luma] / 2^16, with gain = peak delta * weight * strength / texture peak. */
    uint32_t gain[256];
    for (unsigned l = 0; l < 256u; l++) {
        const float g = (float)GRAIN_DELTA_AT_PEAK * 65536.0f / (float)GRAIN_TEX_PEAK *
                        ((float)midtone_weight(l) / 255.0f) * film->grain_strength;
        gain[l] = (uint32_t)(g + 0.5f);
    }
    /* The seed picks where the tile starts. */
    const uint32_t hash = seed * 2654435761u;
    const uint32_t dx = hash >> 24, dy = (hash >> 16) & GRAIN_TEX_MASK;
    for (uint32_t y = 0; y < image->height; y++) {
        const int16_t *trow = tex + ((y + dy) & GRAIN_TEX_MASK) * GRAIN_TEX_DIM;
        uint8_t *px = fe_row(image, y);
        for (uint32_t x = 0; x < image->width; x++, px += fe_pixel_step(image)) {
            const int32_t v = (int32_t)trow[(x + dx) & GRAIN_TEX_MASK] * (int32_t)gain[fe_luma(px[0], px[1], px[2])];
            const int delta = v >= 0 ? (int)((v + 32768) >> 16) : -(int)((32768 - v) >> 16); /* round half away from 0 */
            if (delta != 0) {
                px[0] = fe_clamp_u8(px[0] + delta);
                px[1] = fe_clamp_u8(px[1] + delta);
                px[2] = fe_clamp_u8(px[2] + delta);
            }
        }
    }
    alloc->release(tex);
    return FE_OK;
}
