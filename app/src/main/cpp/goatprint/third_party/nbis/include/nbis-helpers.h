/* goatprint: glib-free replacement for libfprint's nbis-helpers.h.
 * NBIS (NIST) is public domain; libfprint's copy swapped malloc/free for
 * g_malloc/g_free, which this header maps back to libc. */
#pragma once
#include <stdlib.h>
#include <stdio.h>
#include <stddef.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>

static inline void *gp_nbis_xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) { fputs("nbis: out of memory\n", stderr); abort(); }
    return p;
}
static inline void *gp_nbis_xrealloc(void *o, size_t n) {
    void *p = realloc(o, n ? n : 1);
    if (!p) { fputs("nbis: out of memory\n", stderr); abort(); }
    return p;
}
#define g_malloc(n) gp_nbis_xmalloc(n)
#define g_realloc(p, n) gp_nbis_xrealloc((p), (n))
#define g_free(p) free(p)
#define g_assert(x) do { if (!(x)) { fprintf(stderr, "nbis assert: %s\n", #x); abort(); } } while (0)

#define ASSERT_SIZE_MUL(a, b) \
    g_assert((b) == 0 || (size_t)(a) <= SIZE_MAX / (size_t)(b))
#define ASSERT_INT_MUL(a, b) \
    g_assert((b) == 0 || ((long long)(a) * (long long)(b)) < INT_MAX)
