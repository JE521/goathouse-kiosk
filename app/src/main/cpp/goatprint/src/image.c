#include <stdlib.h>

#include "goatprint/biomini.h"

/* The sensor lies sideways under the prism. Raw x becomes output y (rotated
 * 90 degrees counter-clockwise, same orientation as the vendor SDK) and both
 * axes are resampled to 500 dpi around the frame centre. */
gp_status gp_image_normalize(const gp_image *raw, const gp_device_info *in, gp_image *out) {
    (void)in;
    if (!raw || !raw->pixels || raw->width != GP_RAW_W || raw->height != GP_RAW_H) return GP_ERR_ARG;
    uint8_t *px = malloc(GP_IMG_W * GP_IMG_H);
    if (!px) return GP_ERR_NOMEM;
    const uint8_t *src = raw->pixels;
    const double cx = GP_RAW_W / 2.0, cy = GP_RAW_H / 2.0;

    for (int oy = 0; oy < GP_IMG_H; oy++) {
        /* output row oy (top = fingertip) maps to raw x, right to left */
        double rx = cx + ((GP_IMG_H / 2.0) - oy - 0.5) / GP_SCALE_X;
        int x = (int)rx;
        double fx = rx - x;
        if (x < 0) { x = 0; fx = 0; }
        if (x >= GP_RAW_W - 1) { x = GP_RAW_W - 2; fx = 1; }
        for (int ox = 0; ox < GP_IMG_W; ox++) {
            double ry = cy + (ox - GP_IMG_W / 2.0 + 0.5) / GP_SCALE_Y;
            int y = (int)ry;
            double fy = ry - y;
            if (y < 0) { y = 0; fy = 0; }
            if (y >= GP_RAW_H - 1) { y = GP_RAW_H - 2; fy = 1; }
            const uint8_t *p = src + y * GP_RAW_W + x;
            double v = (p[0] * (1 - fx) + p[1] * fx) * (1 - fy) + (p[GP_RAW_W] * (1 - fx) + p[GP_RAW_W + 1] * fx) * fy;
            px[oy * GP_IMG_W + ox] = (uint8_t)(v + 0.5);
        }
    }
    out->width = GP_IMG_W;
    out->height = GP_IMG_H;
    out->pixels = px;
    out->contrast = raw->contrast;
    out->finger_metric = raw->finger_metric;
    return GP_OK;
}
