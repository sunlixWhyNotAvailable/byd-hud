#include <jni.h>
#include <link.h>
#include <cstdint>
#include <cstring>
#include <mutex>

// shortcut: private ABI pinned to the 5.25.0.1 arm64 engine; re-trace before porting.
// Only owned instances receive copied vtables. Shared code, palettes and caches stay untouched.
static uintptr_t library;
static uintptr_t styleTable[36], tileTable[10], labelTable[10];
static std::once_flag tablesReady;

static int findLibrary(dl_phdr_info* info, size_t, void* result) {
    const char* name = strrchr(info->dlpi_name, '/');
    if (strcmp(name ? name + 1 : info->dlpi_name, "libwaze.so")) return 0;
    static const unsigned char expected[] = {
        0xb3,0xd3,0xfc,0x2f,0x0c,0xa4,0xb3,0x89,0x45,0x05,0x88,0x5b,0xc4,0xbe,0xfd,0xd4};
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_NOTE) continue;
        const char* cursor = reinterpret_cast<const char*>(info->dlpi_addr + p.p_vaddr);
        const char* end = cursor + p.p_memsz;
        while (cursor + sizeof(ElfW(Nhdr)) <= end) {
            const auto* note = reinterpret_cast<const ElfW(Nhdr)*>(cursor);
            const char* owner = cursor + sizeof *note;
            const char* desc = owner + ((note->n_namesz + 3) & ~3u);
            const char* next = desc + ((note->n_descsz + 3) & ~3u);
            if (next > end || next <= cursor) break;
            if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz == 4 &&
                    !memcmp(owner, "GNU", 4) && note->n_descsz == sizeof expected &&
                    !memcmp(desc, expected, sizeof expected))
                *static_cast<uintptr_t*>(result) = info->dlpi_addr;
            cursor = next;
        }
    }
    return 1;
}
template<class T> static T read(const void* object, size_t offset) {
    T value; memcpy(&value, static_cast<const char*>(object) + offset, sizeof value); return value;
}
struct Shared {
    void* object;
    void* control;
    ~Shared() { reinterpret_cast<void(*)(void*)>(library + 0x2bfe4cc)(this); }
};
static_assert(sizeof(Shared) == 16, "Pinned nontrivial shared_ptr sret ABI");
struct Color { float r, g, b, a; };
struct Road { Color fill, border; uint8_t rest[28]; };
struct Polygon { Color fill; uint8_t rest[96]; };
static_assert(sizeof(Road) == 60 && sizeof(Polygon) == 112, "Pinned style return ABI");
static Road road(void* self, const void* category) {
    Road value = reinterpret_cast<Road(*)(void*, const void*)>(library + 0x3bf71ec)(self, category);
    value.fill = value.border = {1, 1, 1, 1};
    return value;
}
static Polygon polygon(void* self, const void* category) {
    Polygon value = reinterpret_cast<Polygon(*)(void*, const void*)>(library + 0x3bf72a8)(self, category);
    value.fill = {0.025f, 0.025f, 0.025f, 1};
    return value;
}
static Color background(void*) { return {0, 0, 0, 1}; }
static bool noNames(void*) { return false; }
static void tileLabels(void* self, const void* frame) {
    // Empty text input also hides retained text nodes; preserve the original shield/image collection.
    const uintptr_t emptyText[9] = {};
    reinterpret_cast<void(*)(void*, const void*)>(library + 0x2f86248)(self, emptyText);
    reinterpret_cast<void(*)(void*, const void*)>(library + 0x2f8633c)(self,
            static_cast<const char*>(frame) + 0x28);
}
static void polygonLabels(void* self, const void* frame) {
    reinterpret_cast<void(*)(void*, const void*)>(library + 0x2f976b8)(self, frame);
    void* root = read<void*>(self, 8);
    if (root) reinterpret_cast<void(*)(void*, bool)>(library + 0x338290c)(root, false);
}
static void initializeTables() {
    memcpy(styleTable, reinterpret_cast<void*>(library + 0x439ed88), sizeof styleTable);
    styleTable[2 + 0x28 / 8] = reinterpret_cast<uintptr_t>(road);
    styleTable[2 + 0x30 / 8] = reinterpret_cast<uintptr_t>(polygon);
    styleTable[2 + 0x78 / 8] = reinterpret_cast<uintptr_t>(background);
    styleTable[2 + 0xc8 / 8] = reinterpret_cast<uintptr_t>(noNames);
    memcpy(tileTable, reinterpret_cast<void*>(library + 0x42f09a8), sizeof tileTable);
    tileTable[4] = reinterpret_cast<uintptr_t>(tileLabels);
    memcpy(labelTable, reinterpret_cast<void*>(library + 0x42f2990), sizeof labelTable);
    labelTable[4] = reinterpret_cast<uintptr_t>(polygonLabels);
}
static void error(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT void JNICALL
Java_com_bydhud_mapcapture_WazeHudStyle_attach(JNIEnv* env, jclass, jstring mapId) {
    uintptr_t base = 0;
    dl_iterate_phdr(findLibrary, &base);
    if (!base || !mapId) { error(env, "HUD style: unsupported or unloaded Waze engine"); return; }
    library = base; // Called only on Waze's shared render thread.
    const char* id = env->GetStringUTFChars(mapId, nullptr);
    if (!id) return;
    // Read-only libc++ long-string view for the stock registry lookup; no ownership transfer.
    struct StringView { size_t capacity, size; const char* data; } key{(strlen(id) + 1) | 1u, strlen(id), id};
    Shared registry = reinterpret_cast<Shared(*)()>(base + 0x2c81e7c)();
    if (!registry.object) {
        env->ReleaseStringUTFChars(mapId, id);
        error(env, "HUD style: map registry unavailable"); return;
    }
    Shared api = reinterpret_cast<Shared(*)(void*, const void*)>(base + 0x2c82600)(registry.object, &key);
    env->ReleaseStringUTFChars(mapId, id);
    if (!api.object || read<uintptr_t>(api.object, 0) != base + 0x42cbcd0) {
        error(env, "HUD style: owned map API identity mismatch"); return;
    }
    void* service = read<void*>(api.object, 0x150);
    void* style = service ? read<void*>(service, 0x10) : nullptr;
    void* map = service ? read<void*>(service, 0x180) : nullptr;
    void* renderer = map ? read<void*>(map, 8) : nullptr;
    if (style && read<uintptr_t>(style, 0) == reinterpret_cast<uintptr_t>(styleTable + 2)) return;
    if (!style || read<uintptr_t>(style, 0) != base + 0x439ed98 ||
            !renderer || read<uintptr_t>(renderer, 0) != base + 0x42ed5b0) {
        error(env, "HUD style: per-map style/renderer identity mismatch"); return;
    }
    uintptr_t first = read<uintptr_t>(renderer, 0x38), last = read<uintptr_t>(renderer, 0x40);
    if (!first || last < first || (last - first) % 16 || last - first > 4096) {
        error(env, "HUD style: invalid owned layer collection"); return;
    }
    void *tile = nullptr, *labels = nullptr;
    for (uintptr_t at = first; at < last; at += 16) {
        void* layer = read<void*>(reinterpret_cast<void*>(at), 0);
        if (!layer) continue;
        uintptr_t table = read<uintptr_t>(layer, 0);
        if (table == base + 0x42f09b8) tile = layer;
        if (table == base + 0x42f29a0) labels = layer;
    }
    if (!tile || !labels) { error(env, "HUD style: required owned text layers absent"); return; }
    std::call_once(tablesReady, initializeTables);
    *static_cast<uintptr_t**>(style) = styleTable + 2;
    *static_cast<uintptr_t**>(tile) = tileTable + 2;
    *static_cast<uintptr_t**>(labels) = labelTable + 2;
}
