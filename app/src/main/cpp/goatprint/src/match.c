#include "goatprint/match.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include <bozorth.h>
#include <lfs.h>

/* NBIS keeps verbosity flags as globals that the host must define. */
int verbose_bozorth = 0;
int m1_xyt = 0;

/* Box mean over a (2r+1)^2 window using an integral image (edges clamped). */
static void box_mean(const double *src, double *dst, int w, int h, int r) {
    double *ii = calloc((size_t)(w + 1) * (h + 1), sizeof *ii);
    if (!ii) return;
    for (int y = 0; y < h; y++) {
        double row = 0;
        for (int x = 0; x < w; x++) {
            row += src[y * w + x];
            ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row;
        }
    }
    for (int y = 0; y < h; y++) {
        int y0 = y - r < 0 ? 0 : y - r, y1 = y + r + 1 > h ? h : y + r + 1;
        for (int x = 0; x < w; x++) {
            int x0 = x - r < 0 ? 0 : x - r, x1 = x + r + 1 > w ? w : x + r + 1;
            double s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0];
            dst[y * w + x] = s / ((y1 - y0) * (x1 - x0));
        }
    }
    free(ii);
}

/* Local contrast normalisation: evens out pressure/exposure differences and
 * blanks areas without ridge structure, which roughly doubles the minutiae
 * MINDTCT finds on this sensor (see docs/PROTOCOL.md). */
static uint8_t *enhance(const gp_image *img) {
    int w = img->width, h = img->height;
    size_t n = (size_t)w * h;
    double *a = malloc(n * sizeof *a), *m = malloc(n * sizeof *m), *v = malloc(n * sizeof *v);
    uint8_t *out = malloc(n), *tmp = malloc(n);
    if (!a || !m || !v || !out || !tmp) { free(a); free(m); free(v); free(out); free(tmp); return NULL; }
    for (size_t i = 0; i < n; i++) a[i] = img->pixels[i];
    box_mean(a, m, w, h, 12);
    for (size_t i = 0; i < n; i++) { double d = a[i] - m[i]; a[i] = d * d; }
    box_mean(a, v, w, h, 12);
    for (size_t i = 0; i < n; i++) {
        double sd = sqrt(v[i] > 0 ? v[i] : 0);
        double o = sd > 12 ? 128 + (img->pixels[i] - m[i]) / (sd + 8) * 50 : 255;
        tmp[i] = (uint8_t)(o < 0 ? 0 : o > 255 ? 255 : o);
    }
    /* light 3x3 smoothing */
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            int s = 0, c = 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int yy = y + dy, xx = x + dx;
                    if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue;
                    s += tmp[yy * w + xx] * (dx || dy ? 1 : 4); c += dx || dy ? 1 : 4;
                }
            out[y * w + x] = (uint8_t)(s / c);
        }
    free(a); free(m); free(v); free(tmp);
    return out;
}

static int by_quality_desc(const void *a, const void *b) {
    return (int)((const gp_minutia *)b)->quality - (int)((const gp_minutia *)a)->quality;
}

gp_status gp_extract(const gp_image *img, gp_template *out) {
    if (!img || !img->pixels) return GP_ERR_ARG;
    MINUTIAE *mins = NULL;
    int *qmap = NULL, *dmap = NULL, *lcmap = NULL, *lfmap = NULL, *hcmap = NULL;
    int mw, mh, bw, bh, bd;
    unsigned char *bdata = NULL;
    const double ppmm = GP_IMG_DPI / 25.4;

    /* MINDTCT wants dark ridges on a light background, which is what the sensor gives. */
    /* GP_NO_ENHANCE=1: feed the image as-is (for evaluating external preprocessing) */
    const char *ne = getenv("GP_NO_ENHANCE");
    uint8_t *px = ne && *ne == '1' ? NULL : enhance(img);
    if (!px && ne && *ne == '1') { px = malloc((size_t)img->width * img->height); if (px) memcpy(px, img->pixels, (size_t)img->width * img->height); }
    if (!px) return GP_ERR_NOMEM;
    int r = get_minutiae(&mins, &qmap, &dmap, &lcmap, &lfmap, &hcmap, &mw, &mh, &bdata, &bw, &bh, &bd,
                         px, img->width, img->height, 8, ppmm, &g_lfsparms_V2);
    free(px);
    if (r) return GP_ERR_IO;

    static gp_minutia all[MAX_BOZORTH_MINUTIAE * 4];
    int n = 0;
    for (int i = 0; i < mins->num && n < (int)(sizeof all / sizeof all[0]); i++) {
        MINUTIA *m = mins->list[i];
        int x, y, t;
        lfs2nist_minutia_XYT(&x, &y, &t, m, img->width, img->height);
        if (t > 180) t -= 360;
        int q = (int)(m->reliability * 100.0 + 0.5);
        all[n].x = (int16_t)x;
        all[n].y = (int16_t)y;
        all[n].theta = (int16_t)t;
        all[n].quality = (uint8_t)(q > 100 ? 100 : q);
        all[n].type = m->type == RIDGE_ENDING ? 1 : 2;
        n++;
    }
    /* keep the most reliable minutiae, like bozorth's own pruning */
    qsort(all, (size_t)n, sizeof all[0], by_quality_desc);
    out->count = n < GP_MAX_MINUTIAE ? n : GP_MAX_MINUTIAE;
    memcpy(out->m, all, (size_t)out->count * sizeof all[0]);

    free_minutiae(mins);
    free(qmap); free(dmap); free(lcmap); free(lfmap); free(hcmap); free(bdata);
    return GP_OK;
}

static void to_xyt(const gp_template *t, struct xyt_struct *x) {
    x->nrows = t->count;
    for (int i = 0; i < t->count; i++) {
        x->xcol[i] = t->m[i].x;
        x->ycol[i] = t->m[i].y;
        x->thetacol[i] = t->m[i].theta;
    }
}

int gp_match(const gp_template *probe, const gp_template *gallery) {
    int idx;
    return gp_match_best(probe, gallery, 1, &idx);
}

int gp_match_best(const gp_template *probe, const gp_template *gallery, int n, int *best_index) {
    struct xyt_struct *p = calloc(1, sizeof *p), *g = calloc(1, sizeof *g);
    if (!p || !g) { free(p); free(g); return -1; }
    to_xyt(probe, p);
    int plen = bozorth_probe_init(p);
    int best = -1;
    *best_index = -1;
    for (int i = 0; i < n; i++) {
        to_xyt(&gallery[i], g);
        bozorth_gallery_init(g);
        int s = bozorth_to_gallery(plen, p, g);
        if (s > best) { best = s; *best_index = i; }
    }
    free(p); free(g);
    return best;
}

static void put16(uint8_t *b, int v) { b[0] = (uint8_t)v; b[1] = (uint8_t)(v >> 8); }
static int16_t get16(const uint8_t *b) { return (int16_t)(b[0] | b[1] << 8); }

size_t gp_template_serialize(const gp_template *t, uint8_t *buf, size_t cap) {
    size_t need = 8 + (size_t)t->count * 8;
    if (cap < need) return 0;
    memcpy(buf, "GPT1", 4);
    put16(buf + 4, t->count);
    put16(buf + 6, 0);
    uint8_t *p = buf + 8;
    for (int i = 0; i < t->count; i++, p += 8) {
        put16(p, t->m[i].x); put16(p + 2, t->m[i].y); put16(p + 4, t->m[i].theta);
        p[6] = t->m[i].quality; p[7] = t->m[i].type;
    }
    return need;
}

gp_status gp_template_parse(gp_template *t, const uint8_t *buf, size_t len) {
    if (len < 8 || memcmp(buf, "GPT1", 4)) return GP_ERR_ARG;
    int n = (uint16_t)get16(buf + 4);
    if (n > GP_MAX_MINUTIAE || len < 8 + (size_t)n * 8) return GP_ERR_ARG;
    t->count = n;
    const uint8_t *p = buf + 8;
    for (int i = 0; i < n; i++, p += 8) {
        t->m[i].x = get16(p); t->m[i].y = get16(p + 2); t->m[i].theta = get16(p + 4);
        t->m[i].quality = p[6]; t->m[i].type = p[7];
    }
    return GP_OK;
}
