// Delta Android's first native host: Gambatte GB/GBC through the libretro ABI.
// No ROMs, copyrighted BIOS files or compiled third-party cores are stored here.
#ifndef DELTA_HOST_TEST
#include <jni.h>
#endif
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <cstdio>
#include <iterator>
#include <string>
#include <vector>
#include <mutex>
#include <map>
#include <cmath>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>

namespace {
using environment_t = bool (*)(unsigned, void *);
using video_t = void (*)(const void *, unsigned, unsigned, size_t);
using audio_t = void (*)(int16_t, int16_t);
using audio_batch_t = size_t (*)(const int16_t *, size_t);
using input_poll_t = void (*)();
using input_state_t = int16_t (*)(unsigned, unsigned, unsigned, unsigned);
struct GameInfo { const char *path; const void *data; size_t size; const char *meta; };
struct SystemInfo { const char *name; const char *version; const char *extensions; bool need_fullpath; bool block_extract; };
struct Geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct Timing { double fps, sample_rate; };
struct AVInfo { Geometry geometry; Timing timing; };
struct Variable { const char *key; const char *value; };

void *core = nullptr;
void (*retro_init)() = nullptr;
void (*retro_deinit)() = nullptr;
void (*retro_set_environment)(environment_t) = nullptr;
void (*retro_set_video_refresh)(video_t) = nullptr;
void (*retro_set_audio_sample)(audio_t) = nullptr;
void (*retro_set_audio_sample_batch)(audio_batch_t) = nullptr;
void (*retro_set_input_poll)(input_poll_t) = nullptr;
void (*retro_set_input_state)(input_state_t) = nullptr;
void (*retro_get_system_info)(SystemInfo *) = nullptr;
void (*retro_get_system_av_info)(AVInfo *) = nullptr;
bool (*retro_load_game)(const GameInfo *) = nullptr;
void (*retro_unload_game)() = nullptr;
void (*retro_run)() = nullptr;
size_t (*retro_serialize_size)() = nullptr;
bool (*retro_serialize)(void *, size_t) = nullptr;
bool (*retro_unserialize)(const void *, size_t) = nullptr;
void *(*retro_get_memory_data)(unsigned) = nullptr;
size_t (*retro_get_memory_size)(unsigned) = nullptr;

bool initialized = false, gameLoaded = false;
unsigned pixelFormat = 0, buttonBits = 0;
int sampleRate = 48000;
double framesPerSecond = 59.727500569606;
unsigned frameWidth = 160, frameHeight = 144;
constexpr unsigned MAX_DIMENSION = 1024;
std::mutex coreMutex;
std::map<std::string, std::string> variables;
uint64_t coreFingerprint = 0, romFingerprint = 0;
bool canWriteRam = false;
std::string callbackError;
void (*retro_cheat_reset)() = nullptr;
void (*retro_cheat_set)(unsigned, bool, const char *) = nullptr;

uint64_t fingerprint(const uint8_t *data, size_t size) {
    uint64_t value = 14695981039346656037ULL;
    for (size_t i = 0; i < size; ++i) value = (value ^ data[i]) * 1099511628211ULL;
    return value;
}
uint64_t fingerprintFile(const std::string &path) {
    std::ifstream stream(path, std::ios::binary);
    uint64_t value = 14695981039346656037ULL;
    char buffer[8192];
    if (!stream) return 0;
    while (stream.read(buffer, sizeof(buffer)) || stream.gcount()) {
        for (std::streamsize i = 0; i < stream.gcount(); ++i)
            value = (value ^ static_cast<uint8_t>(buffer[i])) * 1099511628211ULL;
    }
    return stream.bad() ? 0 : value;
}
// Durable replacement: an interrupted/failed write never truncates the last save.
std::string atomicWrite(const std::string &path, const uint8_t *data, size_t size) {
    const auto temp = path + ".tmp";
    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return "No se pudo abrir el archivo temporal de guardado.";
    size_t offset = 0;
    bool ok = true;
    while (offset < size) {
        const auto n = ::write(fd, data + offset, size - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { ok = false; break; }
        offset += static_cast<size_t>(n);
    }
    if (ok && ::fsync(fd) != 0) ok = false;
    if (::close(fd) != 0) ok = false;
    if (ok && std::rename(temp.c_str(), path.c_str()) == 0) {
        const auto parent = path.substr(0, path.find_last_of('/'));
        const int directory = ::open(parent.c_str(), O_RDONLY | O_DIRECTORY);
        if (directory >= 0) { ::fsync(directory); ::close(directory); }
        return "";
    }
    std::remove(temp.c_str());
    return "No se pudo guardar; se conserva el archivo anterior.";
}
std::string systemDir, saveDir, romPath;
std::vector<uint8_t> romBytes;
std::vector<uint32_t> pixels(160u * 144u, 0xff000000u);
std::vector<int16_t> samples;
constexpr size_t MAX_SAMPLES = 8192;

bool environment(unsigned cmd, void *data) {
    if (!data) return false;
    switch (cmd) {
        case 3: // GET_CAN_DUPE
            *static_cast<bool *>(data) = true; return true;
        case 9: // GET_SYSTEM_DIRECTORY
            *static_cast<const char **>(data) = systemDir.c_str(); return true;
        case 10: // SET_PIXEL_FORMAT: 0 RGB1555, 1 XRGB8888, 2 RGB565
            if (*static_cast<unsigned *>(data) > 2) return false;
            pixelFormat = *static_cast<unsigned *>(data);
            return true;
        case 15: { // GET_VARIABLE - default core settings
            auto *v = static_cast<Variable *>(data);
            auto entry = v->key ? variables.find(v->key) : variables.end();
            v->value = entry == variables.end() ? nullptr : entry->second.c_str();
            return v->value != nullptr;
        }
        case 16: { // SET_VARIABLES: expose each core's declared default, never guess.
            auto *v = static_cast<Variable *>(data);
            for (size_t i = 0; i < 1024 && v[i].key; ++i) {
                if (!v[i].value) continue;
                std::string description(v[i].value);
                auto start = description.find("; ");
                if (start == std::string::npos) continue;
                auto end = description.find('|', start + 2);
                variables[v[i].key] = description.substr(start + 2, end - start - 2);
            }
            return true;
        }
        case 18: *static_cast<bool *>(data) = true; return true; // SUPPORT_NO_GAME (no game is never loaded)
        case 52: *static_cast<unsigned *>(data) = 0; return true; // legacy core-options API
        case 37: *static_cast<unsigned *>(data) = 3; return true; // video + audio enabled
        case 17: *static_cast<bool *>(data) = false; return true; // GET_VARIABLE_UPDATE
        case 30: // GET_CORE_ASSETS_DIRECTORY
        case 31: // GET_SAVE_DIRECTORY
            *static_cast<const char **>(data) = saveDir.c_str(); return true;
        case 39: // GET_LANGUAGE: English
            *static_cast<unsigned *>(data) = 0; return true;
        default: return false;
    }
}

void video(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || data == reinterpret_cast<const void *>(static_cast<intptr_t>(-1))) return;
    const unsigned bytesPerPixel = pixelFormat == 1 ? 4u : 2u;
    if (!width || !height || width > MAX_DIMENSION || height > MAX_DIMENSION ||
        pitch < width * bytesPerPixel || pitch > MAX_DIMENSION * 16u) {
        callbackError = "El núcleo devolvió un framebuffer inválido.";
        return;
    }
    frameWidth = width;
    frameHeight = height;
    pixels.resize(static_cast<size_t>(width) * height);
    for (unsigned y = 0; y < height; ++y) {
        const auto *row = static_cast<const uint8_t *>(data) + pitch * y;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t out;
            if (pixelFormat == 1) {
                uint32_t color;
                std::memcpy(&color, row + x * 4, 4);
                out = 0xff000000u | (color & 0x00ffffffu);
            } else {
                uint16_t color;
                std::memcpy(&color, row + x * 2, 2);
                if (pixelFormat == 2) {
                    const uint32_t r = (color >> 11) & 31, g = (color >> 5) & 63, b = color & 31;
                    out = 0xff000000u | ((r * 255 / 31) << 16) | ((g * 255 / 63) << 8) | (b * 255 / 31);
                } else {
                    const uint32_t r = (color >> 10) & 31, g = (color >> 5) & 31, b = color & 31;
                    out = 0xff000000u | ((r * 255 / 31) << 16) | ((g * 255 / 31) << 8) | (b * 255 / 31);
                }
            }
            pixels[y * width + x] = out;
        }
    }
}
void audio(int16_t l, int16_t r) {
    if (samples.size() + 2 > MAX_SAMPLES) return;
    samples.push_back(l); samples.push_back(r);
}
size_t audioBatch(const int16_t *data, size_t frames) {
    if (!data || !frames) return 0;
    const size_t capacity = (MAX_SAMPLES - samples.size()) / 2;
    if (frames > capacity) callbackError = "Desbordamiento del búfer de audio.";
    const size_t count = std::min(capacity, frames);
    samples.insert(samples.end(), data, data + count * 2);
    return frames; // core ran all frames; excess samples are intentionally dropped
}
void pollInput() {}
int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != 1 || index != 0 || id >= 16) return 0;
    return (buttonBits & (1u << id)) ? 1 : 0;
}
template <typename T> bool symbol(const char *name, T &target) {
    target = reinterpret_cast<T>(dlsym(core, name));
    return target != nullptr;
}
std::string saveRam() {
    if (!gameLoaded || !canWriteRam) return "";
    for (unsigned memory = 0; memory <= 1; ++memory) {
        const auto *data = static_cast<const uint8_t *>(retro_get_memory_data(memory));
        const size_t n = retro_get_memory_size(memory);
        if (!data || !n) continue;
        if (n > 8u * 1024u * 1024u) return "Tamaño de guardado inválido.";
        const auto error = atomicWrite(romPath + (memory == 0 ? ".srm" : ".rtc"), data, n);
        if (!error.empty()) return error;
    }
    return "";
}
std::string restoreRam() {
    // Stage every memory region before touching the core; preserve invalid saves.
    std::vector<uint8_t> restored[2];
    for (unsigned memory = 0; memory <= 1; ++memory) {
        const size_t n = retro_get_memory_size(memory);
        if (!retro_get_memory_data(memory) || !n) continue;
        if (n > 8u * 1024u * 1024u) return "Tamaño de guardado inválido.";
        const auto path = romPath + (memory == 0 ? ".srm" : ".rtc");
        std::ifstream stream(path, std::ios::binary | std::ios::ate);
        if (!stream) {
            if (::access(path.c_str(), F_OK) == 0) return "No se pudo leer el guardado existente.";
            continue;
        }
        if (stream.tellg() != static_cast<std::streamoff>(n))
            return "Guardado incompatible; se conserva sin sobrescribirlo.";
        restored[memory].resize(n);
        stream.seekg(0);
        if (!stream.read(reinterpret_cast<char *>(restored[memory].data()), static_cast<std::streamsize>(n)))
            return "Guardado incompleto; se conserva sin sobrescribirlo.";
    }
    for (unsigned memory = 0; memory <= 1; ++memory)
        if (!restored[memory].empty()) std::memcpy(retro_get_memory_data(memory), restored[memory].data(), restored[memory].size());
    canWriteRam = true;
    return "";
}
void closeCore() {
    saveRam();
    if (gameLoaded && retro_unload_game) retro_unload_game();
    gameLoaded = false;
    canWriteRam = false;
    if (initialized && retro_deinit) retro_deinit();
    initialized = false;
    if (core) dlclose(core);
    core = nullptr;
    romBytes.clear();
    samples.clear();
    romPath.clear();
    variables.clear();
    callbackError.clear();
    buttonBits = 0;
}
std::string openCore(const std::string &lib, const std::string &rom, const std::string &dataDir) {
    closeCore();
    systemDir = saveDir = dataDir;
    romPath = rom;
    core = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!core) return std::string("No se pudo cargar el núcleo: ") + dlerror();
    bool ok =
        symbol("retro_init", retro_init) &&
        symbol("retro_deinit", retro_deinit) &&
        symbol("retro_set_environment", retro_set_environment) &&
        symbol("retro_set_video_refresh", retro_set_video_refresh) &&
        symbol("retro_set_audio_sample", retro_set_audio_sample) &&
        symbol("retro_set_audio_sample_batch", retro_set_audio_sample_batch) &&
        symbol("retro_set_input_poll", retro_set_input_poll) &&
        symbol("retro_set_input_state", retro_set_input_state) &&
        symbol("retro_get_system_info", retro_get_system_info) &&
        symbol("retro_get_system_av_info", retro_get_system_av_info) &&
        symbol("retro_load_game", retro_load_game) &&
        symbol("retro_unload_game", retro_unload_game) &&
        symbol("retro_run", retro_run) &&
        symbol("retro_serialize_size", retro_serialize_size) &&
        symbol("retro_serialize", retro_serialize) &&
        symbol("retro_unserialize", retro_unserialize) &&
        symbol("retro_get_memory_data", retro_get_memory_data) &&
        symbol("retro_get_memory_size", retro_get_memory_size);
    if (!ok) { closeCore(); return "El motor no implementa la API libretro necesaria."; }
    symbol("retro_cheat_reset", retro_cheat_reset);
    symbol("retro_cheat_set", retro_cheat_set);
    coreFingerprint = fingerprintFile(lib);
    romFingerprint = fingerprintFile(rom);
    if (!coreFingerprint || !romFingerprint) { closeCore(); return "No se pudo verificar la ROM o el núcleo."; }
    pixelFormat = 0;
    pixels.assign(160u * 144u, 0xff000000u);
    frameWidth = 160; frameHeight = 144;
    retro_set_environment(environment);
    retro_set_video_refresh(video);
    retro_set_audio_sample(audio);
    retro_set_audio_sample_batch(audioBatch);
    retro_set_input_poll(pollInput);
    retro_set_input_state(input);
    retro_init();
    initialized = true;
    SystemInfo info{};
    retro_get_system_info(&info);
    if (!info.need_fullpath) {
        std::ifstream file(rom, std::ios::binary | std::ios::ate);
        if (!file) { closeCore(); return "No se puede leer la ROM seleccionada."; }
        const auto length = file.tellg();
        if (length <= 0 || length > 64u * 1024u * 1024u) {
            closeCore(); return "Archivo vacío o demasiado grande.";
        }
        file.seekg(0);
        romBytes.resize(static_cast<size_t>(length));
        if (!file.read(reinterpret_cast<char *>(romBytes.data()), length)) {
            closeCore(); return "No se pudo leer la ROM completa.";
        }
    }
    GameInfo game{romPath.c_str(), romBytes.empty() ? nullptr : romBytes.data(), romBytes.size(), nullptr};
    if (!retro_load_game(&game)) { closeCore(); return "El núcleo rechazó la ROM."; }
    gameLoaded = true;
    const auto ramError = restoreRam();
    if (!ramError.empty()) { closeCore(); return ramError; }
    AVInfo av{};
    retro_get_system_av_info(&av);
    if (av.timing.sample_rate >= 8000 && av.timing.sample_rate <= 192000) {
        sampleRate = static_cast<int>(av.timing.sample_rate + 0.5);
    } else { closeCore(); return "Frecuencia de audio inválida."; }
    if (!std::isfinite(av.timing.fps) || av.timing.fps < 20 || av.timing.fps > 240 ||
        !av.geometry.base_width || !av.geometry.base_height ||
        av.geometry.base_width > MAX_DIMENSION || av.geometry.base_height > MAX_DIMENSION ||
        av.geometry.max_width > MAX_DIMENSION || av.geometry.max_height > MAX_DIMENSION) {
        closeCore(); return "Geometría o frecuencia de vídeo inválida.";
    }
    framesPerSecond = av.timing.fps;
    frameWidth = av.geometry.base_width; frameHeight = av.geometry.base_height;
    pixels.assign(static_cast<size_t>(frameWidth) * frameHeight, 0xff000000u);
    return "";
}
std::string statePath(int slot) {
    return romPath + ".state" + std::to_string(slot);
}
// Versioned little-endian envelope; raw 0.2 states remain readable at their
// original per-ROM path. New states bind the actual ROM and core binary.
void append64(std::vector<uint8_t> &out, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i) out.push_back(static_cast<uint8_t>(value >> (8 * i)));
}
uint64_t read64(const uint8_t *p) {
    uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i) value |= static_cast<uint64_t>(p[i]) << (8 * i);
    return value;
}
std::string saveState(int slot) {
    if (!gameLoaded || slot < 0 || slot > 9) return "No hay juego o ranura válida.";
    const size_t size = retro_serialize_size();
    if (!size || size > 16u * 1024u * 1024u) return "El núcleo no permite guardar estados.";
    std::vector<uint8_t> snapshot(size);
    if (!retro_serialize(snapshot.data(), snapshot.size())) return "No se pudo serializar el estado.";
    std::vector<uint8_t> file{'D','L','T','A','S','T','0','2'};
    append64(file, coreFingerprint); append64(file, romFingerprint);
    append64(file, size); append64(file, fingerprint(snapshot.data(), size));
    file.insert(file.end(), snapshot.begin(), snapshot.end());
    return atomicWrite(statePath(slot), file.data(), file.size());
}
std::string loadState(int slot) {
    if (!gameLoaded || slot < 0 || slot > 9) return "No hay juego o ranura válida.";
    const size_t n = retro_serialize_size();
    if (!n || n > 16u * 1024u * 1024u) return "Estados no soportados.";
    std::ifstream file(statePath(slot), std::ios::binary | std::ios::ate);
    if (!file) return "No existe un estado guardado en esta ranura.";
    const auto length = file.tellg();
    if (length != static_cast<std::streamoff>(n) && length != static_cast<std::streamoff>(n + 40))
        return "Estado truncado o incompatible con el núcleo.";
    file.seekg(0);
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    if (!file.read(reinterpret_cast<char *>(bytes.data()), length)) return "No se pudo leer el estado.";
    const uint8_t *payload = bytes.data();
    if (bytes.size() == n + 40) {
        if (std::memcmp(payload, "DLTAST02", 8) || read64(payload + 8) != coreFingerprint ||
            read64(payload + 16) != romFingerprint || read64(payload + 24) != n)
            return "Este estado pertenece a otra ROM o versión del núcleo.";
        payload += 40;
        if (read64(bytes.data() + 32) != fingerprint(payload, n)) return "Estado dañado; no se ha cargado.";
    }
    // Some cores mutate memory before reporting failure. Restore a rollback copy.
    std::vector<uint8_t> rollback(n);
    if (!retro_serialize(rollback.data(), n)) return "No se pudo proteger el estado actual.";
    if (!retro_unserialize(payload, n)) {
        if (!retro_unserialize(rollback.data(), n)) callbackError = "El núcleo no pudo restaurar su estado anterior.";
        return "El núcleo rechazó el estado guardado.";
    }
    samples.clear();
    return "";
}
#ifndef DELTA_HOST_TEST
std::string fromJString(JNIEnv *env, jstring str) {
    if (!str) return {};
    const char *s = env->GetStringUTFChars(str, nullptr);
    if (!s) return {};
    std::string result(s);
    env->ReleaseStringUTFChars(str, s);
    return result;
}
#endif
}

#ifndef DELTA_HOST_TEST
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_open(JNIEnv *env, jobject,
                                                    jstring lib, jstring rom, jstring dir) {
    std::lock_guard<std::mutex> lock(coreMutex);
    const std::string message = openCore(fromJString(env, lib), fromJString(env, rom), fromJString(env, dir));
    return message.empty() ? nullptr : env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_step(JNIEnv *env, jobject,
                                                    jintArray frame, jshortArray sound, jint buttons) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!gameLoaded || !frame || !sound) return -1;
    buttonBits = static_cast<unsigned>(buttons);
    samples.clear();
    retro_run();
    if (!callbackError.empty() || env->GetArrayLength(frame) < static_cast<jsize>(pixels.size()) ||
        env->GetArrayLength(sound) < static_cast<jsize>(samples.size())) return -1;
    env->SetIntArrayRegion(frame, 0, static_cast<jsize>(pixels.size()),
                           reinterpret_cast<const jint *>(pixels.data()));
    const size_t len = std::min(static_cast<size_t>(env->GetArrayLength(sound)), samples.size());
    if (len) env->SetShortArrayRegion(sound, 0, static_cast<jsize>(len), samples.data());
    return static_cast<jint>(len);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_saveState(JNIEnv *env, jobject, jint slot) {
    std::lock_guard<std::mutex> lock(coreMutex);
    const auto error = saveState(slot);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_loadState(JNIEnv *env, jobject, jint slot) {
    std::lock_guard<std::mutex> lock(coreMutex);
    const auto error = loadState(slot);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_flushSave(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(coreMutex);
    const auto error = saveRam();
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_sampleRate(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(coreMutex);
    return sampleRate;
}
extern "C" JNIEXPORT void JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(coreMutex);
    closeCore();
}
extern "C" JNIEXPORT jdouble JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_frameRate(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(coreMutex);
    return framesPerSecond;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_videoSize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(coreMutex);
    return static_cast<jint>((frameWidth << 16) | frameHeight);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_setCheats(JNIEnv *env, jobject, jobjectArray codes) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!gameLoaded || !retro_cheat_set || !retro_cheat_reset) return env->NewStringUTF("Cheats no soportados.");
    retro_cheat_reset();
    const jsize n = codes ? env->GetArrayLength(codes) : 0;
    if (n > 128) return env->NewStringUTF("Demasiados códigos.");
    for (jsize i = 0; i < n; ++i) {
        auto code = static_cast<jstring>(env->GetObjectArrayElement(codes, i));
        const auto text = fromJString(env, code);
        env->DeleteLocalRef(code);
        if (text.size() > 128) return env->NewStringUTF("Código demasiado largo.");
        retro_cheat_set(static_cast<unsigned>(i), true, text.c_str());
    }
    return nullptr;
}
#endif
