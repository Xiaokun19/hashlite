/*
 * keccak_native.c —— 薄封装实现（Linux 与 Android 共用；只依赖 libc）
 */
#include "keccak_native.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/auxv.h>

/* vendored 汇编提供的两套实现（OpenSSL 3.0.13 的 keccak1600-armv8.S） */
extern size_t SHA3_absorb(uint64_t A[25], const unsigned char *inp, size_t len, size_t r);
extern void SHA3_squeeze(const uint64_t A[25], unsigned char *out, size_t len, size_t r);
extern size_t SHA3_absorb_cext(uint64_t A[25], const unsigned char *inp, size_t len, size_t r);
extern void SHA3_squeeze_cext(const uint64_t A[25], unsigned char *out, size_t len, size_t r);

/* AArch64 HWCAP：sha3 = bit 17（内核 asm/hwcaps.h；与 App 里 Hwcap.SHA3 一致）
 * Android bionic 不一定暴露 <asm/hwcap.h>，所以这里直接写位号。 */
#define KC_HWCAP_SHA3 (1UL << 17)
#ifndef AT_HWCAP
#define AT_HWCAP 16
#endif

static int g_variant = -1; /* -1 = 未定 */

/* ------------------------------------------------------------------ 探测 */

int kc_have_sha3ext(void) {
    return (getauxval(AT_HWCAP) & KC_HWCAP_SHA3) ? 1 : 0;
}

int kc_variant_default(void) {
    if (g_variant < 0) {
        /* 安全默认：普通变体。cext 必须靠实测上位（作者注释里 X2 反例） */
        g_variant = 0;
    }
    return g_variant;
}

void kc_set_variant(int cext) {
    /* 双保险：没有 SHA3 扩展的机器上永远不允许切到 cext（调用方亦有门控） */
    g_variant = (cext && kc_have_sha3ext()) ? 1 : 0;
}

const char *kc_variant_name(void) {
    return kc_variant_default() ? "cext(EOR3)" : "plain";
}

/* ------------------------------------------------------------------ 核心 */

static size_t absorb_of(kc_ctx *ctx, const unsigned char *in, size_t len) {
    return ctx->cext ? SHA3_absorb_cext(ctx->A, in, len, ctx->rate)
                     : SHA3_absorb(ctx->A, in, len, ctx->rate);
}

static void squeeze_of(kc_ctx *ctx, unsigned char *out, size_t len) {
    if (ctx->cext) {
        SHA3_squeeze_cext(ctx->A, out, len, ctx->rate);
    } else {
        SHA3_squeeze(ctx->A, out, len, ctx->rate);
    }
}

void kc_init_variant(kc_ctx *ctx, size_t rate, int cext) {
    memset(ctx, 0, sizeof *ctx);
    ctx->rate = rate;
    ctx->cext = cext ? 1 : 0;
}

void kc_init(kc_ctx *ctx, size_t rate) {
    kc_init_variant(ctx, rate, kc_variant_default());
}

void kc_update(kc_ctx *ctx, const void *data, size_t len) {
    const unsigned char *in = (const unsigned char *)data;
    size_t bsz = ctx->rate;
    size_t num = ctx->bufsz;

    if (num) {
        size_t rem = bsz - num;
        if (len < rem) {
            memcpy(ctx->buf + num, in, len);
            ctx->bufsz = num + len;
            return;
        }
        memcpy(ctx->buf + num, in, rem);
        absorb_of(ctx, ctx->buf, bsz);
        ctx->bufsz = 0;
        in += rem;
        len -= rem;
    }

    if (len >= bsz) {
        size_t rem = (ctx->cext ? SHA3_absorb_cext(ctx->A, in, len, bsz)
                                : SHA3_absorb(ctx->A, in, len, bsz));
        ctx->bufsz = rem;
        memcpy(ctx->buf, in + len - rem, rem);
    } else {
        memcpy(ctx->buf, in, len);
        ctx->bufsz = len;
    }
}

void kc_final(kc_ctx *ctx, void *out, size_t outlen, unsigned char domain) {
    size_t bsz = ctx->rate;
    size_t num = ctx->bufsz;

    memset(ctx->buf + num, 0, bsz - num);
    ctx->buf[num] = domain;
    ctx->buf[bsz - 1] |= 0x80;
    absorb_of(ctx, ctx->buf, bsz);
    squeeze_of(ctx, (unsigned char *)out, outlen);
}

/* ------------------------------------------------------------------ 自检 */

static int hex_eq(const unsigned char *got, size_t n, const char *want_hex) {
    char buf[2 * 64 + 1];
    static const char *d = "0123456789abcdef";
    if (n > 64) return 0;
    for (size_t i = 0; i < n; i++) {
        buf[2 * i] = d[got[i] >> 4];
        buf[2 * i + 1] = d[got[i] & 15];
    }
    buf[2 * n] = 0;
    return strcmp(buf, want_hex) == 0;
}

static int one_shot(const void *data, size_t len, void *out, size_t outlen,
                    size_t rate, unsigned char domain, int cext) {
    kc_ctx ctx;
    kc_init_variant(&ctx, rate, cext);
    kc_update(&ctx, data, len);
    kc_final(&ctx, out, outlen, domain);
    return 0;
}

int kc_self_test(void) {
    static const char *ABC = "abc";
    static const char *MSG1M = NULL; /* 1e6 x 'a' 另算 */
    unsigned char out[64];
    int fails = 0;
    /*没有 SHA3 扩展的机器上禁止触碰 cext（EOR3 指令会 SIGILL），
     * 自检只覆盖 plain——usable 的判定不应依赖老旧 CPU 跑不了的东西。 */
    int has_sha3 = kc_have_sha3ext();

    /* 空串 */
    one_shot("", 0, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 0);
    if (!hex_eq(out, 32, "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a")) fails++;
    if (has_sha3) {
        one_shot("", 0, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 1);
        if (!hex_eq(out, 32, "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a")) fails++;
    }

    /* "abc" */
    one_shot(ABC, 3, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 0);
    if (!hex_eq(out, 32, "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532")) fails++;
    if (has_sha3) {
        one_shot(ABC, 3, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 1);
        if (!hex_eq(out, 32, "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532")) fails++;
    }
    one_shot(ABC, 3, out, 64, KC_RATE_SHA3_512, KC_DOMAIN_SHA3, 0);
    if (!hex_eq(out, 64, "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e"
                         "10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0")) fails++;
    one_shot(ABC, 3, out, 16, KC_RATE_SHAKE128, KC_DOMAIN_SHAKE, 0);
    if (!hex_eq(out, 16, "5881092dd818bf5cf8a3ddb793fbcba7")) fails++;
    one_shot(ABC, 3, out, 32, KC_RATE_SHAKE256, KC_DOMAIN_SHAKE, 0);
    if (!hex_eq(out, 32, "483366601360a8771c6863080cc4114d8db44530f8f1e1ee4f94ea37e78b5739")) fails++;

    /* 1e6 x 'a' */
    unsigned char *big = (unsigned char *)malloc(1000000);
    if (big) {
        memset(big, 'a', 1000000);
        one_shot(big, 1000000, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 0);
        if (!hex_eq(out, 32, "5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1")) fails++;
        if (has_sha3) {
            one_shot(big, 1000000, out, 32, KC_RATE_SHA3_256, KC_DOMAIN_SHA3, 1);
            if (!hex_eq(out, 32, "5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1")) fails++;
        }
        free(big);
    } else {
        fails++;
    }
    (void)MSG1M;
    return fails;
}

/* ------------------------------------------------------------------ 测速 */

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static double bench_one(const unsigned char *buf, size_t len, size_t rate,
                        int cext, int iters) {
    unsigned char out[32];
    double best = 0;
    /* 先预热一轮：第一遍要付页错误/指令缓存的钱，不能算进结果 */
    {
        kc_ctx warm;
        kc_init_variant(&warm, rate, cext);
        kc_update(&warm, buf, len);
        kc_final(&warm, out, 32, KC_DOMAIN_SHA3);
    }
    for (int r = 0; r < 3; r++) {
        kc_ctx ctx;
        double t0 = now_sec();
        for (int i = 0; i < iters; i++) {
            kc_init_variant(&ctx, rate, cext);
            kc_update(&ctx, buf, len);
            kc_final(&ctx, out, 32, KC_DOMAIN_SHA3);
        }
        double dt = now_sec() - t0;
        double mbps = (double)len * iters / dt / (1024.0 * 1024.0);
        if (mbps > best) best = mbps;
    }
    return best;
}

void kc_bench(double *plain_mbps, double *cext_mbps, size_t bytes) {
    unsigned char *buf = (unsigned char *)malloc(bytes);
    if (!buf) {
        if (plain_mbps) *plain_mbps = 0;
        if (cext_mbps) *cext_mbps = 0;
        return;
    }
    unsigned int seed = 12345;
    for (size_t i = 0; i < bytes; i++) {
        seed = seed * 1103515245u + 12345u;
        buf[i] = (unsigned char)(seed >> 16);
    }
    int iters = 8;
    if (plain_mbps) *plain_mbps = bench_one(buf, bytes, KC_RATE_SHA3_256, 0, iters);
    /* cext 只在真的支持 SHA3 扩展时才测：否则执行 EOR3 会 SIGILL（返回 0 = 跳过） */
    if (cext_mbps) *cext_mbps = kc_have_sha3ext() ? bench_one(buf, bytes, KC_RATE_SHA3_256, 1, iters) : 0;
    free(buf);
}

void kc_choose_variant_by_bench(size_t bytes) {
    if (!kc_have_sha3ext()) {
        g_variant = 0;
        return;
    }
    if (bytes < 16u * 1024 * 1024) bytes = 16u * 1024 * 1024; /* 小样本噪声大，下限 16 MiB */
    double plain = 0, cext = 0;
    kc_bench(&plain, &cext, bytes);
    /* 只有实测更快（>3%）才切 cext —— 作者注释里 X2 类核会显著更慢 */
    g_variant = (cext > plain * 1.03) ? 1 : 0;
}