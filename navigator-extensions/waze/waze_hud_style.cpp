#include <jni.h>
#include <link.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <atomic>
#include <mutex>
#include <functional>
#include <string>
#include <string_view>
#include <cmath>

// shortcut: private ABI pinned to the 5.25.0.1 arm64 engine; re-trace before porting.
// Only owned instances receive copied vtables and opt into the amber shader uniform.
static uintptr_t library;
static uintptr_t styleTable[36], tileTable[10], labelTable[10], positionTable[10];
static std::once_flag tablesReady;
// Accessed only on Waze's render thread; report transitions, never each frame.
static char positionStatus[384] = "position hook not called", reportedStatus[384];
static char sourceStatus[192];
// ShaderMaterial owns the bound Shader Ref. The helper retains only non-owning identities, which
// are cleared on each WazeHudMap scene epoch and whenever the current marker disappears.
static std::atomic<uint64_t> requestedSceneEpoch{0};
static uint64_t attachedSceneEpoch;
static uintptr_t bindingLibrary;
static void *bindingMapApi, *bindingRenderer;
static void *boundMaterial, *boundShader;
static void *failedMaterial, *failedShader;
static std::u32string failedSource;
static char failedSourceStatus[192];
static bool failedAttempted, failedSourceComparable;
static uint32_t privateShaderAttempts;
static char privateShaderStatus[256] = "private_shader=not_loaded";
static std::string markerCodeUtf8;
static std::u32string markerCode;

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
// Only the owned scene's render executor reads/writes this palette.
static Color roadColor{65.f/255,70.f/255,76.f/255,1}, casingColor{76.f/255,82.f/255,88.f/255,1};
static Color routeColor{102.f/255,181.f/255,1,1}, backgroundColor{10.f/255,12.f/255,14.f/255,1};
static Color rgb(jint color) {
    auto value=static_cast<uint32_t>(color);
    return {float((value>>16)&255)/255, float((value>>8)&255)/255, float(value&255)/255, 1};
}
struct VariantValue {
    alignas(8) uint8_t bytes[24];
    ~VariantValue() { reinterpret_cast<void(*)(void*)>(library + 0x31014a4)(this); }
};
static_assert(sizeof(VariantValue) == 24, "Pinned nontrivial Variant sret ABI");
struct ShaderString {
    const char32_t* data;
    ~ShaderString() { reinterpret_cast<void(*)(void*)>(library + 0x2f6fd7c)(this); }
};
static_assert(sizeof(ShaderString) == 8 && sizeof(char32_t) == 4, "Pinned nontrivial UTF-32 String sret ABI");
struct EngineString {
    const char32_t* data;
    ~EngineString() { reinterpret_cast<void(*)(void*)>(library + 0x2f6fd7c)(this); }
};
static_assert(sizeof(EngineString) == 8, "Pinned Godot String layout");
struct EngineResourceRef {
    void* object;
    ~EngineResourceRef() { reinterpret_cast<void(*)(void*)>(library + 0x2f70838)(this); }
};
static_assert(sizeof(EngineResourceRef) == 8, "Pinned Ref<Resource> x8 sret ABI");
static bool inspectShaderSource(void* shader,
        std::u32string* snapshot = nullptr, const std::u32string* expected = nullptr,
        bool* matchesExpected = nullptr) {
    if (matchesExpected) *matchesExpected = false;
    if (!shader || read<uintptr_t>(shader, 0) != library + 0x4357a90) {
        snprintf(sourceStatus, sizeof sourceStatus, "code=unavailable; Shader identity guard");
        if (snapshot) snapshot->clear();
        return false;
    }
    // get_code returns an owned String copy; its pinned pre-copy virtual call is a no-op.
    ShaderString code = reinterpret_cast<ShaderString(*)(void*)>(library + 0x3654668)(shader);
    int32_t length = reinterpret_cast<int32_t(*)(const void*)>(library + 0x3080efc)(&code);
    if (length < 0 || length > 262144 || (length && !code.data)) {
        if (snapshot) snapshot->clear();
        snprintf(sourceStatus, sizeof sourceStatus, "code=unavailable; String length guard; chars=%d", length);
        return false;
    }
    if (code.data && code.data[length] != 0) {
        if (snapshot) snapshot->clear();
        snprintf(sourceStatus, sizeof sourceStatus, "code=unavailable; String terminator guard; chars=%d", length);
        return false;
    }
    std::u32string_view source = length ? std::u32string_view(code.data, length) : std::u32string_view{};
    bool patched = source == markerCode;
    if (matchesExpected && expected) *matchesExpected = source == *expected;
    // Only copy the current source for a new/changed binding attempt. Repeated failed states
    // compare against the retained snapshot in place and do not allocate a copy per frame.
    if (snapshot && (!expected || source != *expected)) snapshot->assign(source);
    const char* identity = source.empty() ? "empty" : source.find(U"uniform bool bydhud_amber") == source.npos ? "stock_or_other"
            : patched ? "configured_hud" : "other";
    snprintf(sourceStatus, sizeof sourceStatus,
            "code=%s; chars=%d; fp64=%016llx; source_decl=%u; source_branch=%u",
            identity, length, static_cast<unsigned long long>(std::hash<std::u32string_view>{}(source)),
            unsigned(source.find(U"uniform bool bydhud_amber") != source.npos),
            unsigned(source.find(U"if (bydhud_amber)") != source.npos));
    return patched;
}
static void clearMarkerShaderState() {
    boundMaterial = boundShader = nullptr;
    failedMaterial = failedShader = nullptr;
    failedSource.clear();
    failedSourceStatus[0] = 0;
    failedAttempted = failedSourceComparable = false;
}
static void rememberShaderFailure(void* material, void* shader, const std::u32string& source,
        const char* sourceStatusOverride = nullptr) {
    failedMaterial = material;
    failedShader = shader;
    failedSource = source;
    const char* status = sourceStatusOverride ? sourceStatusOverride : sourceStatus;
    snprintf(failedSourceStatus, sizeof failedSourceStatus, "%s", status);
    failedSourceComparable = strncmp(status, "code=unavailable", 16) != 0;
    failedAttempted = true;
}
static bool loadPrivateShader(void* currentShader, EngineResourceRef& output) {
    output.object = nullptr;
    // This mode-0 Shader is a fresh factory object. Its mounted-PCK code is overwritten below
    // with the exact input-APK-derived source; no shared Shader or ResourceCache entry is changed.
    EngineString path{nullptr}, typeHint{nullptr};
    reinterpret_cast<void(*)(void*, const char*)>(library + 0x2f6eb48)(
            &path, "res://Shaders/user_location.gdshader");
    reinterpret_cast<void(*)(void*, const char*)>(library + 0x2f6eb48)(&typeHint, "");
    int32_t errorCode = -1;
    EngineResourceRef loaded = reinterpret_cast<EngineResourceRef(*)(const void*, const void*, int32_t, int32_t*)>(
            library + 0x30375c0)(&path, &typeHint, 0, &errorCode); // CACHE_MODE_IGNORE
    ++privateShaderAttempts;
    if (errorCode != 0) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_load_error=%d; attempt=%u; stage=ResourceLoader::load", errorCode, privateShaderAttempts);
        return false;
    }
    if (!loaded.object) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_load_error=0; resource=null; attempt=%u; stage=ResourceLoader::load", privateShaderAttempts);
        return false;
    }
    if (read<uintptr_t>(loaded.object, 0) != library + 0x4357a90) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_rejected; attempt=%u; stage=Shader vtable guard", privateShaderAttempts);
        return false;
    }
    if (loaded.object == currentShader) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_rejected; attempt=%u; stage=factory identity; returned current Shader",
                privateShaderAttempts);
        return false;
    }
    EngineString exactSource{nullptr};
    reinterpret_cast<void(*)(void*, const char*)>(library + 0x2f6eb48)(
            &exactSource, markerCodeUtf8.c_str());
    reinterpret_cast<void(*)(void*, const void*)>(library + 0x36543d4)(loaded.object, &exactSource);
    if (!inspectShaderSource(loaded.object)) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_rejected; attempt=%u; stage=set_code exact readback; compile=unknown; %s",
                privateShaderAttempts, sourceStatus);
        return false;
    }
    // Shader RID is lazy: the verified virtual getter creates it from the stored source.
    uintptr_t shaderRid = reinterpret_cast<uintptr_t(*)(void*)>(library + 0x2c11860)(loaded.object);
    if (!shaderRid) {
        snprintf(privateShaderStatus, sizeof privateShaderStatus,
                "private_shader_rejected; attempt=%u; stage=Shader::get_rid; rid=0; compile=unknown; %s",
                privateShaderAttempts, sourceStatus);
        return false;
    }
    output.object = loaded.object;
    loaded.object = nullptr;
    snprintf(privateShaderStatus, sizeof privateShaderStatus,
            "private_shader=code_stored_exact; rid=valid; cache_mode=IGNORE; source=input_apk_patch; compile=unknown; attempt=%u",
            privateShaderAttempts);
    return true;
}
struct Road { Color fill, border; uint8_t rest[28]; };
struct Polygon { Color fill; uint8_t rest[96]; };
struct Polyline { Color fill; uint8_t rest[104]; };
static_assert(sizeof(Road) == 60 && sizeof(Polygon) == 112, "Pinned style return ABI");
static_assert(sizeof(Polyline) == 120, "Pinned polyline return ABI");
static Road road(void* self, const void* category) {
    Road value = reinterpret_cast<Road(*)(void*, const void*)>(library + 0x3bf71ec)(self, category);
    value.fill = roadColor;
    value.border = casingColor;
    return value;
}
static Polygon polygon(void* self, const void* category) {
    Polygon value = reinterpret_cast<Polygon(*)(void*, const void*)>(library + 0x3bf72a8)(self, category);
    value.fill = backgroundColor;
    return value;
}
static Polyline polyline(void* self, uint64_t category, uint32_t extra) {
    Polyline value = reinterpret_cast<Polyline(*)(void*, uint64_t, uint32_t)>(library + 0x3bf74b8)(self, category, extra);
    // Kind 1 selects Navigation (schema 21/25); traffic kinds 9..18 stay stock.
    if (static_cast<uint32_t>(category) == 1) value.fill = {routeColor.r, routeColor.g, routeColor.b, value.fill.a};
    return value;
}
static Color background(void*) { return backgroundColor; }
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
static void position(void* self, const void* frame) {
    reinterpret_cast<void(*)(void*, const void*)>(library + 0x2f97ed8)(self, frame);
    snprintf(positionStatus, sizeof positionStatus, "ordinary update; current marker absent");
    // Stock creates a separate material per marker; ID 0 is the current position.
    void* node = read<void*>(self, 0x18);
    bool foundCurrentMarker = false;
    for (unsigned count = 0; node && count < 64; ++count, node = read<void*>(node, 0)) {
        if (read<int64_t>(node, 0x10) != 0) continue;
        foundCurrentMarker = true;
        void* marker = read<void*>(node, 0x18);
        void* circle = marker ? read<void*>(marker, 0x38) : nullptr;
        void* accuracy = marker ? read<void*>(marker, 0x40) : nullptr;
        if (circle && accuracy && read<uintptr_t>(accuracy, 0) == library + 0x42f30f8)
            reinterpret_cast<void(*)(void*, bool)>(library + 0x338290c)(circle, false);
        void* material = marker ? read<void*>(marker, 0x28) : nullptr;
        if (!material || read<uintptr_t>(material, 0) != library + 0x42f3088) {
            clearMarkerShaderState();
            snprintf(positionStatus, sizeof positionStatus, "current marker; material guard; vtable=0x%llx",
                    static_cast<unsigned long long>(material ? read<uintptr_t>(material, 0) - library : 0));
            return;
        }
        void* shaderMaterial = read<void*>(material, 8);
        if (!shaderMaterial || read<uintptr_t>(shaderMaterial, 0) != library + 0x43546d8) {
            clearMarkerShaderState();
            snprintf(positionStatus, sizeof positionStatus, "current marker; shader material guard; vtable=0x%llx",
                    static_cast<unsigned long long>(shaderMaterial ? read<uintptr_t>(shaderMaterial, 0) - library : 0));
            return;
        }
        void* shader = read<void*>(shaderMaterial, 0x268);
        bool alreadyBound = shaderMaterial == boundMaterial && shader == boundShader;
        bool sameFailedState = failedAttempted && shaderMaterial == failedMaterial && shader == failedShader;
        std::u32string activeSource;
        bool matchesFailedSource = false;
        bool activeSourcePatched = inspectShaderSource(shader,
                alreadyBound ? nullptr : &activeSource,
                sameFailedState ? &failedSource : nullptr,
                sameFailedState ? &matchesFailedSource : nullptr);
        bool rebound = false;
        if (alreadyBound && activeSourcePatched) {
            // The material owns this Shader Ref. A fresh exact source read confirms it remains live.
        } else {
            if (alreadyBound) {
                // A previously bound Shader changed or failed its getter; take one fresh snapshot
                // before deciding whether this marker state has already failed.
                activeSourcePatched = inspectShaderSource(shader, &activeSource,
                        sameFailedState ? &failedSource : nullptr,
                        sameFailedState ? &matchesFailedSource : nullptr);
            }
            bool sameFailureDiagnostics = sameFailedState && (failedSourceComparable
                    ? matchesFailedSource : !strcmp(sourceStatus, failedSourceStatus));
            if (sameFailureDiagnostics) {
                snprintf(positionStatus, sizeof positionStatus,
                        "current marker; private_shader_error=previous attempt preserved; retry=next scene or marker state; %s",
                        privateShaderStatus);
                return;
            }
            char sourceBeforeAttempt[sizeof sourceStatus];
            snprintf(sourceBeforeAttempt, sizeof sourceBeforeAttempt, "%s", sourceStatus);
            EngineResourceRef privateRef{nullptr};
            if (!loadPrivateShader(shader, privateRef)) {
                rememberShaderFailure(shaderMaterial, shader, activeSource, sourceBeforeAttempt);
                snprintf(positionStatus, sizeof positionStatus,
                        "current marker; %s; active_before=%s",
                        privateShaderStatus, sourceBeforeAttempt);
                return;
            }
            void* privateShader = privateRef.object;
            // set_shader retains a strong Ref in this material; the local factory Ref is released
            // when this block ends, so no process-global Godot RID crosses scene generations.
            reinterpret_cast<void(*)(void*, const void*)>(library + 0x35fd0a4)(
                    shaderMaterial, &privateRef);
            shader = read<void*>(shaderMaterial, 0x268);
            if (shader != privateShader) {
                std::u32string failedNow;
                inspectShaderSource(shader, &failedNow);
                rememberShaderFailure(shaderMaterial, shader, failedNow);
                snprintf(positionStatus, sizeof positionStatus,
                        "current marker; private_shader_error=set_shader readback mismatch; %s",
                        sourceStatus);
                return;
            }
            activeSourcePatched = inspectShaderSource(shader);
            if (!activeSourcePatched) {
                std::u32string failedNow;
                inspectShaderSource(shader, &failedNow);
                rememberShaderFailure(shaderMaterial, shader, failedNow);
                snprintf(positionStatus, sizeof positionStatus,
                        "current marker; private_shader_error=active source guard; compile=unknown; %s",
                        sourceStatus);
                return;
            }
            boundMaterial = shaderMaterial;
            boundShader = shader;
            failedMaterial = failedShader = nullptr;
            failedSource.clear();
            failedSourceStatus[0] = 0;
            failedAttempted = failedSourceComparable = false;
            rebound = true;
        }
        uintptr_t name = 0;
        alignas(8) uint8_t value[24] = {};
        reinterpret_cast<void(*)(void*, const char*, bool)>(library + 0x30d2fac)(
                &name, "bydhud_amber", false);
        reinterpret_cast<void(*)(void*, bool)>(library + 0x310581c)(value, true);
        reinterpret_cast<void(*)(void*, const void*, const void*)>(library + 0x35fb8dc)(
                shaderMaterial, &name, value);
        VariantValue stored = reinterpret_cast<VariantValue(*)(void*, const void*)>(library + 0x35fbaf0)(
                shaderMaterial, &name);
        snprintf(positionStatus, sizeof positionStatus,
                "current marker; private_shader=bound; rebound=%u; source_patched=%u; compile=unknown; amber parameter type=%u bool=%u; shader vtable=0x%llx; %s; pixels not verified",
                unsigned(rebound), unsigned(activeSourcePatched),
                read<unsigned>(stored.bytes, 0), read<uint8_t>(stored.bytes, 8),
                static_cast<unsigned long long>(shader ? read<uintptr_t>(shader, 0) - library : 0), sourceStatus);
        reinterpret_cast<void(*)(void*)>(library + 0x31014a4)(value);
        reinterpret_cast<void(*)(void*)>(library + 0x30d2bd0)(&name);
        return;
    }
    if (!foundCurrentMarker) clearMarkerShaderState();
}
static void initializeTables() {
    memcpy(styleTable, reinterpret_cast<void*>(library + 0x439ed88), sizeof styleTable);
    styleTable[2 + 0x28 / 8] = reinterpret_cast<uintptr_t>(road);
    styleTable[2 + 0x30 / 8] = reinterpret_cast<uintptr_t>(polygon);
    styleTable[2 + 0x40 / 8] = reinterpret_cast<uintptr_t>(polyline);
    styleTable[2 + 0x78 / 8] = reinterpret_cast<uintptr_t>(background);
    styleTable[2 + 0xc8 / 8] = reinterpret_cast<uintptr_t>(noNames);
    memcpy(tileTable, reinterpret_cast<void*>(library + 0x42f09a8), sizeof tileTable);
    tileTable[4] = reinterpret_cast<uintptr_t>(tileLabels);
    memcpy(labelTable, reinterpret_cast<void*>(library + 0x42f2990), sizeof labelTable);
    labelTable[4] = reinterpret_cast<uintptr_t>(polygonLabels);
    memcpy(positionTable, reinterpret_cast<void*>(library + 0x42f2d00), sizeof positionTable);
    positionTable[4] = reinterpret_cast<uintptr_t>(position);
}
static void error(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT void JNICALL
Java_com_bydhud_mapcapture_WazeHudMap_nativePalette(JNIEnv* env, jclass,
        jstring inputSource, jint roadValue, jint casingValue, jint routeValue, jint markerValue, jint backgroundValue) {
    // Called before h() creates the owned scene, after disposal of the previous render target.
    Color marker = rgb(markerValue);
    auto linear = [](float v) { return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f); };
    char srgb[128], linearRgb[128];
    snprintf(srgb, sizeof srgb, "vec3(%.9f, %.9f, %.9f)", marker.r, marker.g, marker.b);
    snprintf(linearRgb, sizeof linearRgb, "vec3(%.9f, %.9f, %.9f)", linear(marker.r), linear(marker.g), linear(marker.b));
    // The original shader comes from this navigator APK, never from the HUD distribution.
    if (!inputSource || env->GetStringUTFLength(inputSource) > 65536) {
        error(env, "HUD palette: invalid input shader size"); return;
    }
    const char* source = env->GetStringUTFChars(inputSource, nullptr);
    if (!source) return;
    std::string code{source};
    env->ReleaseStringUTFChars(inputSource, source);
    if (code.find("uniform bool bydhud_amber = false;") == code.npos || code.find("if (bydhud_amber)") == code.npos) {
        error(env, "HUD palette: input shader lacks opt-in branch"); return;
    }
    auto replace = [&](const char* before, const char* after) {
        size_t offset = code.find(before);
        if (offset == std::string::npos) return false;
        code.replace(offset, strlen(before), after);
        return true;
    };
    if (!replace("vec3(1.0, 0.72, 0.10)", srgb) || !replace("vec3(1.0, 0.477, 0.010)", linearRgb)) {
        error(env, "HUD palette: marker shader color token absent"); return;
    }
    markerCodeUtf8 = code;
    markerCode.assign(code.begin(), code.end());
    roadColor = rgb(roadValue); casingColor = rgb(casingValue);
    routeColor = rgb(routeValue); backgroundColor = rgb(backgroundValue);
    clearMarkerShaderState();
}
extern "C" JNIEXPORT void JNICALL
Java_com_bydhud_mapcapture_WazeHudMap_nativeBeginScene(JNIEnv*, jclass, jlong epoch) {
    if (epoch > 0) requestedSceneEpoch.store(static_cast<uint64_t>(epoch), std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL
Java_com_bydhud_mapcapture_WazeHudStyle_attach(JNIEnv* env, jclass, jstring mapId) {
    uintptr_t base = 0;
    dl_iterate_phdr(findLibrary, &base);
    if (!base || !mapId) { error(env, "HUD style: unsupported or unloaded Waze engine"); return; }
    library = base; // Called only on Waze's shared render thread.
    uint64_t epoch = requestedSceneEpoch.load(std::memory_order_acquire);
    if (epoch != attachedSceneEpoch || base != bindingLibrary) {
        clearMarkerShaderState();
        attachedSceneEpoch = epoch;
        bindingLibrary = base;
        reportedStatus[0] = 0;
        snprintf(positionStatus, sizeof positionStatus, "scene attached; current marker binding pending");
    }
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
    if (api.object != bindingMapApi || renderer != bindingRenderer) {
        clearMarkerShaderState();
        bindingMapApi = api.object;
        bindingRenderer = renderer;
        reportedStatus[0] = 0;
        snprintf(positionStatus, sizeof positionStatus, "owned map scene changed; current marker binding pending");
    }
    bool alreadyAttached = style && read<uintptr_t>(style, 0) == reinterpret_cast<uintptr_t>(styleTable + 2);
    if (alreadyAttached) return;
    if (!style || read<uintptr_t>(style, 0) != base + 0x439ed98 ||
            !renderer || read<uintptr_t>(renderer, 0) != base + 0x42ed5b0) {
        error(env, "HUD style: per-map style/renderer identity mismatch"); return;
    }
    uintptr_t first = read<uintptr_t>(renderer, 0x38), last = read<uintptr_t>(renderer, 0x40);
    if (!first || last < first || (last - first) % 16 || last - first > 4096) {
        error(env, "HUD style: invalid owned layer collection"); return;
    }
    void *tile = nullptr, *labels = nullptr, *userPosition = nullptr;
    for (uintptr_t at = first; at < last; at += 16) {
        void* layer = read<void*>(reinterpret_cast<void*>(at), 0);
        if (!layer) continue;
        uintptr_t table = read<uintptr_t>(layer, 0);
        if (table == base + 0x42f09b8) tile = layer;
        if (table == base + 0x42f29a0) labels = layer;
        if (table == base + 0x42f2d10) userPosition = layer;
        if (table == base + 0x42f0938) {
            // Only the ordinary child at +0x18 has this material layout; +0x08 is immersive.
            void* child = read<void*>(layer, 0x18);
            if (child && read<uintptr_t>(child, 0) == base + 0x42f2d10)
                userPosition = child;
        }
    }
    if (!tile || !labels || !userPosition) {
        char message[128];
        snprintf(message, sizeof message, "HUD style: required owned layers absent (tile=%d labels=%d position=%d)",
                tile != nullptr, labels != nullptr, userPosition != nullptr);
        error(env, message); return;
    }
    clearMarkerShaderState();
    std::call_once(tablesReady, initializeTables);
    *static_cast<uintptr_t**>(style) = styleTable + 2;
    *static_cast<uintptr_t**>(tile) = tileTable + 2;
    *static_cast<uintptr_t**>(labels) = labelTable + 2;
    *static_cast<uintptr_t**>(userPosition) = positionTable + 2;
    snprintf(positionStatus, sizeof positionStatus, "attached; position hook not called");
    reportedStatus[0] = 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bydhud_mapcapture_WazeHudMap_nativeStyleStatus(JNIEnv* env, jclass) {
    if (!strcmp(positionStatus, reportedStatus)) return nullptr;
    jstring result = env->NewStringUTF(positionStatus);
    if (result) snprintf(reportedStatus, sizeof reportedStatus, "%s", positionStatus);
    return result;
}
