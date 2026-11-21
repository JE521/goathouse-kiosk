/* goatprint: the MINUTIA / MINUTIAE structs from NBIS lfs.h (public domain),
 * which libfprint moved into its own header. */
#pragma once

struct fp_minutia {
    int x;
    int y;
    int ex;
    int ey;
    int direction;
    double reliability;
    int type;
    int appearing;
    int feature_id;
    int *nbrs;
    int *ridge_counts;
    int num_nbrs;
};

struct fp_minutiae {
    int alloc;
    int num;
    struct fp_minutia **list;
};
