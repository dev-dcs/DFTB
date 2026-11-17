/*
 * dftbtool - DFTB's built-in, dependency-free command-line helper.
 *
 * Packaged in the APK as libdftbtool.so (see CMakeLists.txt) so the Android installer extracts it,
 * already executable, into the app's nativeLibraryDir. It exists to PROVE the whole provisioning
 * pipeline end to end on a real device - locate the lib<id>.so, stage it, chmod it, then exec it -
 * without shipping a multi-megabyte third-party prebuilt. It also doubles as a real fallback hasher
 * and copier the engines can rely on when no system/busybox equivalent is present.
 *
 * Deliberately self-contained: POSIX + libc only, -std=c11, no exceptions, no OpenSSL. The SHA-256
 * implementation below is a straight transcription of FIPS 180-4. It was verified by building this
 * file for the host and checking the FIPS 180-4 known-answer vectors ("", "abc" and 1,000,000 x 'a')
 * against a reference sha256; there is no automated on-device test of the packaged binary yet.
 *
 * Usage:
 *   dftbtool version            Print "dftbtool <version> (<abi>)".
 *   dftbtool sha256 <file>      Stream <file> and print "<hex>  <file>" (sha256sum format).
 *   dftbtool copy <src> <dst>   Buffered copy of <src> to <dst>.
 *   dftbtool help               Print usage.
 *
 * Exit codes: 0 success; 1 usage error; 2 I/O error.
 */

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <fcntl.h>
#include <unistd.h>

#define DFTBTOOL_VERSION "1.0.0"

#if defined(__aarch64__)
#define DFTBTOOL_ABI "arm64-v8a"
#elif defined(__x86_64__)
#define DFTBTOOL_ABI "x86_64"
#elif defined(__arm__)
#define DFTBTOOL_ABI "armeabi-v7a"
#elif defined(__i386__)
#define DFTBTOOL_ABI "x86"
#else
#define DFTBTOOL_ABI "unknown"
#endif

#define EXIT_OK 0
#define EXIT_USAGE 1
#define EXIT_IO 2

#define IO_BUFFER_SIZE (64 * 1024)

/* ---- SHA-256 (FIPS 180-4) ----------------------------------------------------------------- */

typedef struct {
    uint32_t state[8];
    uint64_t bit_count;
    uint8_t buffer[64];
    size_t buffer_len;
} sha256_ctx;

static const uint32_t SHA256_K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
};

static uint32_t rotr(uint32_t value, unsigned bits) {
    return (value >> bits) | (value << (32u - bits));
}

static void sha256_init(sha256_ctx *ctx) {
    ctx->state[0] = 0x6a09e667u;
    ctx->state[1] = 0xbb67ae85u;
    ctx->state[2] = 0x3c6ef372u;
    ctx->state[3] = 0xa54ff53au;
    ctx->state[4] = 0x510e527fu;
    ctx->state[5] = 0x9b05688cu;
    ctx->state[6] = 0x1f83d9abu;
    ctx->state[7] = 0x5be0cd19u;
    ctx->bit_count = 0;
    ctx->buffer_len = 0;
}

static void sha256_compress(sha256_ctx *ctx, const uint8_t block[64]) {
    uint32_t w[64];
    for (unsigned i = 0; i < 16; ++i) {
        w[i] = ((uint32_t)block[i * 4] << 24) |
               ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) |
               ((uint32_t)block[i * 4 + 3]);
    }
    for (unsigned i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = ctx->state[0];
    uint32_t b = ctx->state[1];
    uint32_t c = ctx->state[2];
    uint32_t d = ctx->state[3];
    uint32_t e = ctx->state[4];
    uint32_t f = ctx->state[5];
    uint32_t g = ctx->state[6];
    uint32_t h = ctx->state[7];

    for (unsigned i = 0; i < 64; ++i) {
        uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + s1 + ch + SHA256_K[i] + w[i];
        uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256_update(sha256_ctx *ctx, const uint8_t *data, size_t len) {
    ctx->bit_count += (uint64_t)len * 8u;
    while (len > 0) {
        size_t space = 64 - ctx->buffer_len;
        size_t take = len < space ? len : space;
        memcpy(ctx->buffer + ctx->buffer_len, data, take);
        ctx->buffer_len += take;
        data += take;
        len -= take;
        if (ctx->buffer_len == 64) {
            sha256_compress(ctx, ctx->buffer);
            ctx->buffer_len = 0;
        }
    }
}

static void sha256_final(sha256_ctx *ctx, uint8_t digest[32]) {
    uint64_t bit_count = ctx->bit_count;
    uint8_t pad = 0x80;
    sha256_update(ctx, &pad, 1);
    uint8_t zero = 0x00;
    while (ctx->buffer_len != 56) {
        sha256_update(ctx, &zero, 1);
    }
    uint8_t length_be[8];
    for (int i = 0; i < 8; ++i) {
        length_be[i] = (uint8_t)(bit_count >> (56 - i * 8));
    }
    /* Feed the length directly; it completes the final block without touching bit_count semantics. */
    memcpy(ctx->buffer + ctx->buffer_len, length_be, 8);
    ctx->buffer_len += 8;
    sha256_compress(ctx, ctx->buffer);
    ctx->buffer_len = 0;

    for (int i = 0; i < 8; ++i) {
        digest[i * 4] = (uint8_t)(ctx->state[i] >> 24);
        digest[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        digest[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        digest[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

/* ---- Commands ----------------------------------------------------------------------------- */

static int cmd_version(void) {
    printf("dftbtool %s (%s)\n", DFTBTOOL_VERSION, DFTBTOOL_ABI);
    return EXIT_OK;
}

static int cmd_help(FILE *out) {
    fputs(
        "dftbtool " DFTBTOOL_VERSION " (" DFTBTOOL_ABI ")\n"
        "Usage:\n"
        "  dftbtool version            Print version and ABI.\n"
        "  dftbtool sha256 <file>      Print the SHA-256 of <file> (sha256sum format).\n"
        "  dftbtool copy <src> <dst>   Copy <src> to <dst>.\n"
        "  dftbtool help               Print this message.\n",
        out);
    return out == stderr ? EXIT_USAGE : EXIT_OK;
}

static int cmd_sha256(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "dftbtool: cannot open %s\n", path);
        return EXIT_IO;
    }

    sha256_ctx ctx;
    sha256_init(&ctx);

    static uint8_t buffer[IO_BUFFER_SIZE];
    for (;;) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n < 0) {
            fprintf(stderr, "dftbtool: read error on %s\n", path);
            close(fd);
            return EXIT_IO;
        }
        if (n == 0) {
            break;
        }
        sha256_update(&ctx, buffer, (size_t)n);
    }
    close(fd);

    uint8_t digest[32];
    sha256_final(&ctx, digest);

    char hex[65];
    static const char HEX[] = "0123456789abcdef";
    for (int i = 0; i < 32; ++i) {
        hex[i * 2] = HEX[(digest[i] >> 4) & 0xF];
        hex[i * 2 + 1] = HEX[digest[i] & 0xF];
    }
    hex[64] = '\0';

    printf("%s  %s\n", hex, path);
    return EXIT_OK;
}

static int cmd_copy(const char *src, const char *dst) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) {
        fprintf(stderr, "dftbtool: cannot open %s\n", src);
        return EXIT_IO;
    }
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (out < 0) {
        fprintf(stderr, "dftbtool: cannot create %s\n", dst);
        close(in);
        return EXIT_IO;
    }

    static uint8_t buffer[IO_BUFFER_SIZE];
    int status = EXIT_OK;
    for (;;) {
        ssize_t n = read(in, buffer, sizeof(buffer));
        if (n < 0) {
            fprintf(stderr, "dftbtool: read error on %s\n", src);
            status = EXIT_IO;
            break;
        }
        if (n == 0) {
            break;
        }
        size_t offset = 0;
        while (offset < (size_t)n) {
            ssize_t written = write(out, buffer + offset, (size_t)n - offset);
            if (written <= 0) {
                fprintf(stderr, "dftbtool: write error on %s\n", dst);
                status = EXIT_IO;
                break;
            }
            offset += (size_t)written;
        }
        if (status != EXIT_OK) {
            break;
        }
    }

    if (status == EXIT_OK && fsync(out) != 0) {
        fprintf(stderr, "dftbtool: fsync error on %s\n", dst);
        status = EXIT_IO;
    }
    close(in);
    close(out);
    return status;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        return cmd_help(stderr);
    }

    const char *command = argv[1];
    if (strcmp(command, "version") == 0) {
        return cmd_version();
    }
    if (strcmp(command, "help") == 0 || strcmp(command, "--help") == 0 || strcmp(command, "-h") == 0) {
        return cmd_help(stdout);
    }
    if (strcmp(command, "sha256") == 0) {
        if (argc != 3) {
            fprintf(stderr, "dftbtool: sha256 requires exactly one <file>\n");
            return EXIT_USAGE;
        }
        return cmd_sha256(argv[2]);
    }
    if (strcmp(command, "copy") == 0) {
        if (argc != 4) {
            fprintf(stderr, "dftbtool: copy requires <src> and <dst>\n");
            return EXIT_USAGE;
        }
        return cmd_copy(argv[2], argv[3]);
    }

    fprintf(stderr, "dftbtool: unknown command '%s'\n", command);
    return cmd_help(stderr);
}
