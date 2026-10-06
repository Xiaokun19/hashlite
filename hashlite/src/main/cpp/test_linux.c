/*
 * test_linux.c —— 在桌面 Linux（proot aarch64，同一颗 CPU）上验证 vendored keccak：
 *   1) 公开向量自检（两种变体）
 *   2) 与系统 OpenSSL 交叉对拍
 *   3) 64 MiB 测速对比（plain vs cext）
 * 编译：gcc -O2 -o test_linux test_linux.c keccak_native.c gen/plain.S -lcrypto
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <openssl/evp.h>

#include "keccak_native.h"

int main(void) {
    printf("HWCAP sha3: %d\n", kc_have_sha3ext());

    int fails = kc_self_test();
    printf("自检（空串/abc/1e6a × 两种变体 + SHA3-512/SHAKE128/256）: %s（%d 个不过）\n",
           fails ? "FAIL" : "OK", fails);

    /* 交叉对拍：1 MiB 伪随机 vs 系统 OpenSSL */
    size_t n = 1 << 20;
    unsigned char *b = malloc(n), o1[32], o2[32];
    unsigned int s = 42;
    for (size_t i = 0; i < n; i++) { s = s * 1103515245u + 12345u; b[i] = s >> 16; }
    kc_ctx c;
    kc_init_variant(&c, KC_RATE_SHA3_256, 0);
    kc_update(&c, b, n);
    kc_final(&c, o1, 32, KC_DOMAIN_SHA3);
    unsigned int l = 0;
    EVP_Digest(b, n, o2, &l, EVP_sha3_256(), NULL);
    printf("与 OpenSSL 交叉对拍（1 MiB）: %s\n", memcmp(o1, o2, 32) == 0 ? "一致 OK" : "!! 不一致");
    free(b);

    double p = 0, x = 0;
    kc_bench(&p, &x, 64 * 1024 * 1024);
    printf("SHA3-256 @ 64 MiB: plain %.1f MB/s | cext %.1f MB/s | cext/plain = %.2fx\n",
           p, x, p > 0 ? x / p : 0);

    kc_choose_variant_by_bench(8 * 1024 * 1024);
    printf("实测自动选择: %s\n", kc_variant_name());

    return fails ? 1 : 0;
}