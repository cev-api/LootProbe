#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <windows.h>

#include "include/generator.h"
#include "include/finders.h"

#ifdef _MSC_VER
#define LP_EXPORT __declspec(dllexport)
#else
#define LP_EXPORT __attribute__((visibility("default")))
#endif

typedef void (__cdecl *setupGenerator_fn)(Generator *, int, uint32_t);
typedef void (__cdecl *applySeed_fn)(Generator *, int, uint64_t);
typedef int  (__cdecl *getBiomeAt_fn)(const Generator *, int, int, int, int);
typedef void (__cdecl *initBiomeColors_fn)(unsigned char [256][3]);
typedef int  (__cdecl *getStructureConfig_fn)(int, int, StructureConfig *);
typedef int  (__cdecl *getStructurePos_fn)(int, int, uint64_t, int, int, Pos *);
typedef int  (__cdecl *isViableStructurePos_fn)(int, Generator *, int, int, uint32_t);

static HMODULE g_lib = NULL;
static setupGenerator_fn p_setupGenerator = NULL;
static applySeed_fn p_applySeed = NULL;
static getBiomeAt_fn p_getBiomeAt = NULL;
static initBiomeColors_fn p_initBiomeColors = NULL;
static getStructureConfig_fn p_getStructureConfig = NULL;
static getStructurePos_fn p_getStructurePos = NULL;
static isViableStructurePos_fn p_isViableStructurePos = NULL;
static char g_last_error[512];

static void set_last_error(const char *msg)
{
    if (!msg) msg = "unknown error";
    strncpy(g_last_error, msg, sizeof(g_last_error) - 1);
    g_last_error[sizeof(g_last_error) - 1] = '\0';
}

static void append_win_error(char *dst, size_t cap, const char *prefix)
{
    DWORD code = GetLastError();
    char buf[256];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, code, 0, buf, (DWORD) sizeof(buf), NULL
    );
    if (n == 0) {
        snprintf(dst, cap, "%s (code=%lu)", prefix, (unsigned long) code);
        return;
    }
    snprintf(dst, cap, "%s: %s", prefix, buf);
}

static int load_symbols(void)
{
    p_setupGenerator = (setupGenerator_fn) GetProcAddress(g_lib, "setupGenerator");
    p_applySeed = (applySeed_fn) GetProcAddress(g_lib, "applySeed");
    p_getBiomeAt = (getBiomeAt_fn) GetProcAddress(g_lib, "getBiomeAt");
    p_initBiomeColors = (initBiomeColors_fn) GetProcAddress(g_lib, "initBiomeColors");
    p_getStructureConfig = (getStructureConfig_fn) GetProcAddress(g_lib, "getStructureConfig");
    p_getStructurePos = (getStructurePos_fn) GetProcAddress(g_lib, "getStructurePos");
    p_isViableStructurePos = (isViableStructurePos_fn) GetProcAddress(g_lib, "isViableStructurePos");

    if (!p_setupGenerator || !p_applySeed || !p_getBiomeAt || !p_initBiomeColors ||
        !p_getStructureConfig || !p_getStructurePos || !p_isViableStructurePos) {
        set_last_error("failed to resolve required cubiomes exports");
        return -1;
    }
    return 0;
}

static int floor_div(int a, int b)
{
    int q = a / b;
    int r = a % b;
    if (r != 0 && ((r > 0) != (b > 0))) q--;
    return q;
}

LP_EXPORT int __cdecl lp_init(const char *cubiomes_path)
{
    if (g_lib != NULL) return 0;
    const char *path = cubiomes_path && cubiomes_path[0] ? cubiomes_path : "cubiomes.dll";
    g_lib = LoadLibraryA(path);
    if (!g_lib) {
        char msg[512];
        append_win_error(msg, sizeof(msg), "LoadLibraryA(cubiomes.dll) failed");
        set_last_error(msg);
        return -1;
    }
    if (load_symbols() != 0) return -2;
    set_last_error("");
    return 0;
}

LP_EXPORT const char* __cdecl lp_last_error(void)
{
    return g_last_error;
}

LP_EXPORT int __cdecl lp_render_map(
    uint64_t seed, int mc, int dim,
    int center_x, int center_z, int radius,
    int width, int height,
    int *out_argb
)
{
    if (!g_lib && lp_init(NULL) != 0) return -1;
    if (!out_argb || width <= 0 || height <= 0 || radius <= 0) {
        set_last_error("invalid render arguments");
        return -2;
    }

    Generator g;
    memset(&g, 0, sizeof(g));
    p_setupGenerator(&g, mc, 0);
    p_applySeed(&g, dim, seed);

    unsigned char biome_colors[256][3];
    p_initBiomeColors(biome_colors);

    for (int py = 0; py < height; py++) {
        for (int px = 0; px < width; px++) {
            double nx = ((px + 0.5) / (double) width) * 2.0 - 1.0;
            double nz = ((py + 0.5) / (double) height) * 2.0 - 1.0;
            int wx = (int) llround(center_x + nx * radius);
            int wz = (int) llround(center_z + nz * radius);

            int id = p_getBiomeAt(&g, 1, wx, 63, wz);
            unsigned int argb;
            if (id >= 0 && id < 256) {
                unsigned char r = biome_colors[id][0];
                unsigned char gg = biome_colors[id][1];
                unsigned char b = biome_colors[id][2];
                argb = 0xFF000000u | (r << 16) | (gg << 8) | b;
            } else {
                argb = 0xFF303030u;
            }
            out_argb[py * width + px] = (int) argb;
        }
    }
    return 0;
}

LP_EXPORT int __cdecl lp_generate_structures(
    uint64_t seed, int mc, int dim,
    int min_x, int min_z, int max_x, int max_z,
    const int *structure_types, int structure_type_count,
    int max_out, int *out_triplets
)
{
    if (!g_lib && lp_init(NULL) != 0) return -1;
    if (!structure_types || structure_type_count <= 0 || max_out <= 0 || !out_triplets) {
        return 0;
    }

    Generator g;
    memset(&g, 0, sizeof(g));
    p_setupGenerator(&g, mc, 0);
    p_applySeed(&g, dim, seed);

    int out_count = 0;
    for (int i = 0; i < structure_type_count; i++) {
        int st = structure_types[i];
        StructureConfig sc;
        memset(&sc, 0, sizeof(sc));
        if (!p_getStructureConfig(st, mc, &sc)) continue;
        if (sc.dim != dim) continue;
        if (sc.regionSize <= 0) continue;

        int chunk_min_x = floor_div(min_x, 16);
        int chunk_max_x = floor_div(max_x, 16);
        int chunk_min_z = floor_div(min_z, 16);
        int chunk_max_z = floor_div(max_z, 16);
        int reg_min_x = floor_div(chunk_min_x, sc.regionSize) - 1;
        int reg_max_x = floor_div(chunk_max_x, sc.regionSize) + 1;
        int reg_min_z = floor_div(chunk_min_z, sc.regionSize) - 1;
        int reg_max_z = floor_div(chunk_max_z, sc.regionSize) + 1;

        for (int rz = reg_min_z; rz <= reg_max_z; rz++) {
            for (int rx = reg_min_x; rx <= reg_max_x; rx++) {
                Pos p;
                if (!p_getStructurePos(st, mc, seed, rx, rz, &p)) continue;
                if (p.x < min_x || p.x > max_x || p.z < min_z || p.z > max_z) continue;
                if (!p_isViableStructurePos(st, &g, p.x, p.z, 0)) continue;

                if (out_count >= max_out) return out_count;
                int o = out_count * 3;
                out_triplets[o + 0] = st;
                out_triplets[o + 1] = p.x;
                out_triplets[o + 2] = p.z;
                out_count++;
            }
        }
    }
    return out_count;
}
