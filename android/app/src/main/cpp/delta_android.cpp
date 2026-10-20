// Delta Android's first native host: Gambatte GB/GBC through the libretro ABI.
// No ROMs, copyrighted BIOS files or compiled third-party cores are stored here.
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <cstdio>
#include <iterator>
#include <string>
#include <vector>

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
            pixelFormat = *static_cast<unsigned *>(data);
            return pixelFormat <= 2;
        case 15: { // GET_VARIABLE - default core settings
            auto *v = static_cast<Variable *>(data);
            v->value = nullptr;
            return false;
        }
        case 16: return true; // SET_VARIABLES
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
    if (width != 160 || height != 144 || pitch < width * (pixelFormat == 1 ? 4u : 2u)) return;
    for (unsigned y = 0; y < 144; ++y) {
        const auto *row = static_cast<const uint8_t *>(data) + pitch * y;
        for (unsigned x = 0; x < 160; ++x) {
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
            pixels[y * 160u + x] = out;
        }
    }
}
void audio(int16_t l, int16_t r) {
    if (samples.size() + 2 > MAX_SAMPLES) return;
    samples.push_back(l); samples.push_back(r);
}
size_t audioBatch(const int16_t *data, size_t frames) {
    const size_t capacity = (MAX_SAMPLES - samples.size()) / 2;
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
void saveRam() {
    if (!gameLoaded || !retro_get_memory_data || !retro_get_memory_size) return;
    const auto *data = static_cast<const char *>(retro_get_memory_data(0));
    const size_t n = retro_get_memory_size(0);
    if (data && n > 0 && n <= 0x20000) {
        std::ofstream stream(romPath + ".srm", std::ios::binary | std::ios::trunc);
        if (stream) stream.write(data, static_cast<std::streamsize>(n));
    }
}
void restoreRam() {
    if (!gameLoaded || !retro_get_memory_data || !retro_get_memory_size) return;
    auto *data = static_cast<char *>(retro_get_memory_data(0));
    const size_t n = retro_get_memory_size(0);
    if (!data || n == 0 || n > 0x20000) return;
    std::ifstream stream(romPath + ".srm", std::ios::binary | std::ios::ate);
    if (!stream || stream.tellg() != static_cast<std::streamoff>(n)) return;
    stream.seekg(0);
    stream.read(data, static_cast<std::streamsize>(n));
}
void closeCore() {
    saveRam();
    if (gameLoaded && retro_unload_game) retro_unload_game();
    gameLoaded = false;
    if (initialized && retro_deinit) retro_deinit();
    initialized = false;
    if (core) dlclose(core);
    core = nullptr;
    romBytes.clear();
    samples.clear();
    romPath.clear();
}
std::string openCore(const std::string &lib, const std::string &rom, const std::string &dataDir) {
    closeCore();
    systemDir = saveDir = dataDir;
    romPath = rom;
    core = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!core) return std::string("No se pudo cargar Gambatte: ") + dlerror();
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
    pixelFormat = 0;
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
        std::ifstream file(rom, std::ios::binary);
        if (!file) { closeCore(); return "No se puede leer la ROM seleccionada."; }
        romBytes.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
        if (romBytes.empty() || romBytes.size() > 8u * 1024u * 1024u) {
            closeCore(); return "Archivo vacío o demasiado grande para Game Boy.";
        }
    }
    GameInfo game{rom.c_str(), romBytes.empty() ? nullptr : romBytes.data(), romBytes.size(), nullptr};
    if (!retro_load_game(&game)) { closeCore(); return "Gambatte rechazó la ROM."; }
    gameLoaded = true;
    restoreRam();
    AVInfo av{};
    retro_get_system_av_info(&av);
    if (av.timing.sample_rate >= 8000 && av.timing.sample_rate <= 192000) {
        sampleRate = static_cast<int>(av.timing.sample_rate + 0.5);
    } else sampleRate = 48000;
    return "";
}
std::string statePath(int slot) {
    return romPath + ".state" + std::to_string(slot);
}
std::string saveState(int slot) {
    if (!gameLoaded || slot < 0 || slot > 9) return "No hay juego o ranura válida.";
    const size_t size = retro_serialize_size();
    if (!size || size > 4u * 1024u * 1024u) return "El núcleo no permite guardar estados.";
    std::vector<uint8_t> snapshot(size);
    if (!retro_serialize(snapshot.data(), snapshot.size()))
        return "No se pudo serializar el estado.";
    const auto path = statePath(slot);
    const auto temp = path + ".tmp";
    std::ofstream file(temp, std::ios::binary | std::ios::trunc);
    if (!file || !file.write(reinterpret_cast<const char *>(snapshot.data()),
                              static_cast<std::streamsize>(snapshot.size()))) {
        std::remove(temp.c_str());
        return "No se pudo escribir el estado.";
    }
    file.close();
    if (std::rename(temp.c_str(), path.c_str()) != 0) {
        std::remove(temp.c_str());
        return "No se pudo finalizar el archivo de estado.";
    }
    return "";
}
std::string loadState(int slot) {
    if (!gameLoaded || slot < 0 || slot > 9) return "No hay juego o ranura válida.";
    const size_t n = retro_serialize_size();
    if (n == 0 || n > 4u * 1024u * 1024u) return "Estados no soportados.";
    std::ifstream file(statePath(slot), std::ios::binary | std::ios::ate);
    if (!file) return "No existe un estado guardado en esta ranura.";
    if (file.tellg() != static_cast<std::streamoff>(n))
        return "Este estado no es compatible con la versión del núcleo.";
    file.seekg(0);
    std::vector<uint8_t> data(n);
    if (!file.read(reinterpret_cast<char *>(data.data()), static_cast<std::streamsize>(n)))
        return "No se pudo leer el estado.";
    if (!retro_unserialize(data.data(), data.size()))
        return "Gambatte rechazó el estado guardado.";
    return "";
}
std::string fromJString(JNIEnv *env, jstring str) {
    if (!str) return {};
    const char *s = env->GetStringUTFChars(str, nullptr);
    if (!s) return {};
    std::string result(s);
    env->ReleaseStringUTFChars(str, s);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_open(JNIEnv *env, jobject,
                                                    jstring lib, jstring rom, jstring dir) {
    const std::string message = openCore(fromJString(env, lib), fromJString(env, rom), fromJString(env, dir));
    return message.empty() ? nullptr : env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_step(JNIEnv *env, jobject,
                                                    jintArray frame, jshortArray sound, jint buttons) {
    if (!gameLoaded || !frame || !sound ||
        env->GetArrayLength(frame) < 160 * 144) return -1;
    buttonBits = static_cast<unsigned>(buttons);
    samples.clear();
    retro_run();
    env->SetIntArrayRegion(frame, 0, 160 * 144,
                           reinterpret_cast<const jint *>(pixels.data()));
    const size_t len = std::min(static_cast<size_t>(env->GetArrayLength(sound)), samples.size());
    if (len) env->SetShortArrayRegion(sound, 0, static_cast<jsize>(len), samples.data());
    return static_cast<jint>(len);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_saveState(JNIEnv *env, jobject, jint slot) {
    const auto error = saveState(slot);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_loadState(JNIEnv *env, jobject, jint slot) {
    const auto error = loadState(slot);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_flushSave(JNIEnv *, jobject) {
    saveRam();
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_sampleRate(JNIEnv *, jobject) {
    return sampleRate;
}
extern "C" JNIEXPORT void JNICALL
Java_com_ylports_deltaandroid_EmulatorBridge_close(JNIEnv *, jobject) { closeCore(); }
