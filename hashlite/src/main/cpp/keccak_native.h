/*
 * keccak_native.h —— Keccak/SHA-3 的 native 薄封装（桌面 Linux 与 Android 共用）
 *
 * 底层是 vendored 的 OpenSSL 3.0.13 汇编（crypto/sha/asm/keccak1600-armv8.pl 的生成物），
 * 它同时提供两套 Keccak-f1600：
 *   - SHA3_absorb/SHA3_squeeze        ：普通变体（NEON/标量）
 *   - SHA3_absorb_cext/SHA3_squeeze_cext：ARMv8.2-SHA3 扩展变体（EOR3/RAX1/XAR/BCAX）
 *
 * 实测（SM8750 大核）：cext 只快 ~15%，而作者的注释说在某些核上反而更慢
 * （Cortex-X2 上 11.3 vs 6.1 cycles/byte），所以**必须运行时选**，不能只看 HWCAP。
 */
#ifndef KECCAK_NATIVE_H
#define KECCAK_NATIVE_H

#include <stddef.h>
#include <stdint.h>

/* rate（字节）= (1600 - 2*capacity)/8 */
#define KC_RATE_SHA3_224 144
#define KC_RATE_SHA3_256 136
#define KC_RATE_SHA3_384 104
#define KC_RATE_SHA3_512 72
#define KC_RATE_SHAKE128 168
#define KC_RATE_SHAKE256 136

/* 首字节 domain：SHA-3 与 Keccak（旧）不同；SHAKE 是 0x1F */
#define KC_DOMAIN_SHA3 0x06
#define KC_DOMAIN_KECCAK 0x01
#define KC_DOMAIN_SHAKE 0x1F

typedef struct {
    uint64_t A[25];
    size_t rate;
    size_t bufsz;
    unsigned char buf[168];
    int cext; /* 1 = 走 SHA3 扩展变体 */
} kc_ctx;

/* ---- 能力探测与变体选择 ---- */
int kc_have_sha3ext(void);              /* HWCAP 有 sha3 位？ */
int kc_variant_default(void);           /* 当前默认（0=普通 1=cext） */
void kc_set_variant(int cext);          /* 强制指定默认变体 */
void kc_choose_variant_by_bench(size_t bytes); /* 小规模实测后自动选（bytes 建议 4~16 MiB） */
const char *kc_variant_name(void);

/* ---- 流式哈希 ---- */
void kc_init(kc_ctx *ctx, size_t rate);
void kc_init_variant(kc_ctx *ctx, size_t rate, int cext);
void kc_update(kc_ctx *ctx, const void *data, size_t len);
void kc_final(kc_ctx *ctx, void *out, size_t outlen, unsigned char domain);

/* ---- 自检与测速 ---- */
int kc_self_test(void);                 /* 0 = 全部向量通过 */
void kc_bench(double *plain_mbps, double *cext_mbps, size_t bytes);

#endif /* KECCAK_NATIVE_H */
