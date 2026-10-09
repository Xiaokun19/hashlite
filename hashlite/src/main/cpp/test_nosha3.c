/*
 * test_nosha3.c —— 模拟"没有 ARMv8.2-SHA3 扩展"的老芯片（如 A73/A72 代），验证：
 *   1) 自检只跑 plain 向量，绝不触碰 cext（在真机上执行 EOR3 会 SIGILL）；
 *   2) 测速里 cext 必须为 0（整段跳过）；
 *   3) 变体选择必须停在 plain；
 *   4) plain 与系统 OpenSSL 交叉对拍仍然正确。
 *
 * 模拟手段（链接期）：--wrap=getauxval 把 keccak_native.c 的 HWCAP 读取换成
 * 永远返回 0 的桩 → kc_have_sha3ext() == 0，等价于老芯片。
 * 真机复现（可选）：用 qemu-aarch64 -cpu cortex-a72 跑不经 --wrap 的构建，
 * CPU 模型本身没有 sha3 位、执行 EOR3 会被 qemu 判非法指令。
 *
 * 编译（aarch64 Linux / proot）：
 *   gcc -O2 -o test_nosha3 test_nosha3.c keccak_native.c keccak1600-armv8.S \
 *       -Wl,--wrap=getauxval -lcrypto
 * 预期（修复后的代码）：全部通过；未修复的旧代码：cext 会被实际执行（测速非 0，
 * 真机上则是 SIGILL 崩溃）。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <openssl/evp.h>

#include "keccak_native.h"

/* --wrap=getauxval：本二进制里所有 getauxval 调用都落到这里，返回 0 = 无任何 HWCAP 位 */
unsigned long __wrap_getauxval(unsigned long type) {
    (void)type;
    return 0;
}

int main(void) {
    printf("=== 模拟无 SHA3 扩展（HWCAP=0） ===\n");
    printf("kc_have_sha3ext() = %d（期望 0）\n", kc_have_sha3ext());
    int ok = 1;

    /* 1) 自检：只应跑 plain；旧代码会在这里触碰 cext */
    int fails = kc_self_test();
    printf("自检: %s（%d 个不过）\n", fails ? "FAIL" : "OK", fails);
    ok &= (fails == 0);

    /* 2) 测速：cext 应为 0（跳过）；旧代码会真的执行 cext（真机 = SIGILL） */
    double p = -1, x = -1;
    kc_bench(&p, &x, 4 * 1024 * 1024);
    printf("测速: plain %.1f MB/s | cext %.1f MB/s（期望 cext = 0.0）\n", p, x);
    ok &= (x == 0.0 && p > 0.0);

    /* 3) 变体选择：必须停在 plain */
    kc_choose_variant_by_bench(4 * 1024 * 1024);
    printf("变体选择: %s（期望 plain）\n", kc_variant_name());
    ok &= (strcmp(kc_variant_name(), "plain") == 0);

    /* 4) plain 与 OpenSSL 对拍 */
    size_t n = 1 << 20;
    unsigned char *b = malloc(n), o1[32], o2[32];
    unsigned int s = 777;
    for (size_t i = 0; i < n; i++) { s = s * 1103515245u + 12345u; b[i] = (unsigned char)(s >> 16); }
    kc_ctx c;
    kc_init(&c, KC_RATE_SHA3_256);
    kc_update(&c, b, n);
    kc_final(&c, o1, 32, KC_DOMAIN_SHA3);
    unsigned int l = 0;
    EVP_Digest(b, n, o2, &l, EVP_sha3_256(), NULL);
    printf("plain 与 OpenSSL 对拍（1 MiB）: %s\n", memcmp(o1, o2, 32) == 0 ? "一致 OK" : "!! 不一致");
    ok &= (memcmp(o1, o2, 32) == 0);
    free(b);

    printf(ok ? "== 全部通过 ==\n" : "== 存在失败 ==\n");
    return ok ? 0 : 1;
}