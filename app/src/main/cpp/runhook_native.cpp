// Inline-hooks SensorEventQueue::read / ASensorEventQueue_getEvents.
// Java dispatch sees the same buffer afterwards, so the Java hook skips value
// edits while this hook is active. /proc/self/maps is intentionally not hidden.
//
// Waveform constants match MotionWaveform.java. Keep the two copies in sync.

#include <android/log.h>
#include <android/sensor.h>
#include <jni.h>
#include <shadowhook.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>

constexpr const char *kTag = "RunHook";
constexpr float kGravity = 9.80665f;
constexpr float kH1 = 1.15f;
constexpr float kH2 = 0.72f;
constexpr float kH3 = 0.28f;
constexpr float kHeel = 2.60f;
constexpr float kLateral = 0.42f;
constexpr float kForward = 0.25f;

struct Snap {
    int active;
    int spoof_pressure;
    int java_owns;
    float counter;
    float step_hz;
    double phase;
    int64_t phase_time_ns;
    float pressure;
    float tilt_roll;
    float tilt_pitch;
    int noise_seed;
};

std::atomic<uint32_t> g_seq{0};
Snap g_snap{};

std::atomic<uint32_t> g_hw_bits{0};
std::atomic<uint32_t> g_hw_valid{0};
std::atomic<uint32_t> g_mono_bits{0};
std::atomic<uint32_t> g_mono_valid{0};

int g_step_encoding = 0;  // 0 unknown, 1 uint64, 2 float
int32_t g_handle_counter = -1;
int32_t g_handle_detector = -1;
int32_t g_event_version = static_cast<int32_t>(sizeof(ASensorEvent));
int64_t g_ndk_emitted = 0;
int64_t g_last_inject_ts = 0;

using read_fn = ssize_t (*)(void *, ASensorEvent *, size_t);
using get_events_fn = int (*)(void *, ASensorEvent *, size_t);
read_fn g_orig_read = nullptr;
get_events_fn g_orig_get_events = nullptr;
thread_local int g_in_hook = 0;

void log_info(const char *msg) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", msg);
}

float unit_gaussian(int seed, int64_t bucket, int channel) {
    uint64_t x = static_cast<uint64_t>(bucket) * 0x9E3779B97F4A7C15ULL
            ^ (static_cast<uint64_t>(static_cast<int64_t>(seed)) * 0xBF58476D1CE4E5B9ULL)
            ^ (static_cast<uint64_t>(channel) * 0x94D049BB133111EBULL);
    x ^= (x >> 30);
    x *= 0xBF58476D1CE4E5B9ULL;
    x ^= (x >> 27);
    x *= 0x94D049BB133111EBULL;
    x ^= (x >> 31);
    uint64_t y = x * 0xD1B54A32D192ED03ULL + static_cast<uint64_t>(channel + 1) * 0x9E3779B97F4A7C15ULL;
    y ^= (y >> 33);
    y *= 0xBF58476D1CE4E5B9ULL;
    y ^= (y >> 29);
    double u1 = static_cast<double>((x >> 11) & 0x1FFFFFULL) / static_cast<double>(0x200000ULL);
    double u2 = static_cast<double>((y >> 11) & 0x1FFFFFULL) / static_cast<double>(0x200000ULL);
    if (u1 < 1.0e-7) u1 = 1.0e-7;
    return static_cast<float>(std::sqrt(-2.0 * std::log(u1)) * std::cos(M_PI * 2.0 * u2));
}

bool read_snap(Snap *out) {
    for (int i = 0; i < 8; i++) {
        uint32_t a = g_seq.load(std::memory_order_acquire);
        if (a & 1u) continue;
        Snap copy = g_snap;
        uint32_t b = g_seq.load(std::memory_order_acquire);
        if (a == b && (b & 1u) == 0u) {
            *out = copy;
            return true;
        }
    }
    return false;
}

void publish_hw(float hw) {
    uint32_t bits = 0;
    std::memcpy(&bits, &hw, sizeof(bits));
    g_hw_bits.store(bits, std::memory_order_release);
    g_hw_valid.store(1u, std::memory_order_release);
}

float monotonic_store(float proposed) {
    if (!std::isfinite(proposed)) proposed = 0.f;
    uint32_t incoming = 0;
    std::memcpy(&incoming, &proposed, sizeof(incoming));
    while (true) {
        uint32_t cur = g_mono_bits.load(std::memory_order_acquire);
        if (g_mono_valid.load(std::memory_order_acquire)) {
            float cur_f = 0.f;
            std::memcpy(&cur_f, &cur, sizeof(cur_f));
            if (!(proposed >= cur_f)) return cur_f;
        }
        if (g_mono_bits.compare_exchange_weak(cur, incoming, std::memory_order_acq_rel)) {
            g_mono_valid.store(1u, std::memory_order_release);
            return proposed;
        }
    }
}

float read_step(const ASensorEvent *event) {
    uint64_t u = event->u64.step_counter;
    float f = event->data[0];
    if (g_step_encoding == 2) return f;
    if (g_step_encoding == 1) return static_cast<float>(u);
    if (u <= 50000000ULL) {
        g_step_encoding = 1;
        return static_cast<float>(u);
    }
    if (f >= 0.f && f <= 50000000.f) {
        g_step_encoding = 2;
        return f;
    }
    g_step_encoding = 1;
    return static_cast<float>(u);
}

void write_step(ASensorEvent *event, float value) {
    if (value < 0.f) value = 0.f;
    if (g_step_encoding == 2) {
        std::memset(event->data, 0, sizeof(float) * 8);
        event->data[0] = value;
        return;
    }
    event->u64.step_counter = static_cast<uint64_t>(value + 0.5f);
}

struct Sample {
    float ax, ay, az;
    float gx, gy, gz;
    float lx, ly, lz;
    float bax, bay, baz;
    float wx, wy, wz;
    float bwx, bwy, bwz;
    float qx, qy, qz, qw;
    float mx, my, mz;
};

Sample sample_at(const Snap &snap, int64_t time_ns) {
    double phase = snap.phase;
    if (snap.phase_time_ns > 0 && time_ns > 0 && snap.step_hz > 0.f) {
        double dt = static_cast<double>(time_ns - snap.phase_time_ns) * 1.0e-9;
        if (dt > 2.0) dt = 2.0;
        if (dt < -2.0) dt = -2.0;
        phase += dt * static_cast<double>(snap.step_hz) * (M_PI * 2.0);
    }
    int64_t bucket = time_ns / 20000000LL;
    if (bucket < 0) bucket = -bucket;
    double seconds = static_cast<double>(time_ns) * 1.0e-9;
    double drift = 0.012 * std::sin(seconds * (M_PI * 2.0) / 23.0 + ((snap.noise_seed & 7) * 0.17));
    double half = phase * 0.5;
    double roll = static_cast<double>(snap.tilt_roll) + 0.020 * std::sin(half) + drift;
    double pitch = static_cast<double>(snap.tilt_pitch) + 0.015 * std::cos(half);
    double yaw = 0.012 * std::sin(half + 0.4);
    float gx = static_cast<float>(kGravity * std::cos(roll) * std::sin(pitch));
    float gy = static_cast<float>(-kGravity * std::sin(roll));
    float gz = static_cast<float>(kGravity * std::cos(roll) * std::cos(pitch));
    double wrapped = std::atan2(std::sin(phase), std::cos(phase));
    double heel = std::exp(-0.5 * std::pow(wrapped / 0.28, 2.0));
    if (wrapped < 0.0) heel *= 0.35;
    double vertical = kH1 * std::sin(phase) + kH2 * std::sin(2.0 * phase - 0.55)
            + kH3 * std::sin(3.0 * phase + 0.35) + kHeel * heel;
    float lx = static_cast<float>(kLateral * std::sin(half) + unit_gaussian(snap.noise_seed, bucket, 1) * 0.035f);
    float ly = static_cast<float>(kForward * std::cos(phase) + unit_gaussian(snap.noise_seed, bucket, 2) * 0.035f);
    float lz = static_cast<float>(vertical + unit_gaussian(snap.noise_seed, bucket, 3) * 0.05f);
    double scale = snap.step_hz <= 0.f ? 1.0 : std::max(0.65, std::min(1.7, static_cast<double>(snap.step_hz) / 3.0));
    Sample s{};
    s.ax = gx + lx;
    s.ay = gy + ly;
    s.az = gz + lz;
    s.gx = gx;
    s.gy = gy;
    s.gz = gz;
    s.lx = lx;
    s.ly = ly;
    s.lz = lz;
    s.wx = static_cast<float>((0.11 * scale) * std::cos(half) + unit_gaussian(snap.noise_seed, bucket, 4) * 0.008);
    s.wy = static_cast<float>((-0.07 * scale) * std::sin(half) + unit_gaussian(snap.noise_seed, bucket, 5) * 0.008);
    s.wz = static_cast<float>((0.045 * scale) * std::cos(half + 0.4) + unit_gaussian(snap.noise_seed, bucket, 6) * 0.006);
    s.bax = static_cast<float>(0.02 * std::sin(seconds * 0.07 + 0.3));
    s.bay = static_cast<float>(0.015 * std::cos(seconds * 0.05));
    s.baz = static_cast<float>(0.018 * std::sin(seconds * 0.04 + 1.1));
    s.bwx = static_cast<float>(0.002 * std::sin(seconds * 0.03));
    s.bwy = static_cast<float>(0.0015 * std::cos(seconds * 0.04));
    s.bwz = static_cast<float>(0.001 * std::sin(seconds * 0.02 + 0.6));
    double cy = std::cos(yaw * 0.5);
    double sy = std::sin(yaw * 0.5);
    double cp2 = std::cos(pitch * 0.5);
    double sp2 = std::sin(pitch * 0.5);
    double cr2 = std::cos(roll * 0.5);
    double sr2 = std::sin(roll * 0.5);
    s.qw = static_cast<float>(cr2 * cp2 * cy + sr2 * sp2 * sy);
    s.qx = static_cast<float>(sr2 * cp2 * cy - cr2 * sp2 * sy);
    s.qy = static_cast<float>(cr2 * sp2 * cy + sr2 * cp2 * sy);
    s.qz = static_cast<float>(cr2 * cp2 * sy - sr2 * sp2 * cy);
    float bx = 20.5f, by = 4.0f, bz = -41.0f;
    float tx = 2.f * (s.qy * bz - s.qz * by);
    float ty = 2.f * (s.qz * bx - s.qx * bz);
    float tz = 2.f * (s.qx * by - s.qy * bx);
    s.mx = bx + s.qw * tx + (s.qy * tz - s.qz * ty) + unit_gaussian(snap.noise_seed, bucket, 7) * 0.15f;
    s.my = by + s.qw * ty + (s.qz * tx - s.qx * tz) + unit_gaussian(snap.noise_seed, bucket, 8) * 0.15f;
    s.mz = bz + s.qw * tz + (s.qx * ty - s.qy * tx) + unit_gaussian(snap.noise_seed, bucket, 9) * 0.15f;
    return s;
}

void write3(float *data, float x, float y, float z) {
    data[0] = x;
    data[1] = y;
    data[2] = z;
}

void apply_event(ASensorEvent *event, const Snap &snap) {
    if (event->version > 0) g_event_version = event->version;
    if (!snap.active && event->type != ASENSOR_TYPE_STEP_COUNTER) return;
    switch (event->type) {
        case ASENSOR_TYPE_STEP_COUNTER: {
            g_handle_counter = event->sensor;
            float hw = read_step(event);
            if (hw >= 0.f && hw < 1.0e9f) publish_hw(hw);
            if (!snap.active) return;
            float proposed = snap.counter >= hw ? snap.counter : hw;
            float shown = monotonic_store(proposed);
            float quantized = std::floor(shown + 0.5f);
            write_step(event, monotonic_store(quantized));
            break;
        }
        case ASENSOR_TYPE_STEP_DETECTOR:
            g_handle_detector = event->sensor;
            if (snap.active) event->data[0] = 1.f;
            break;
        case ASENSOR_TYPE_PRESSURE:
            if (snap.spoof_pressure && std::isfinite(snap.pressure)) event->pressure = snap.pressure;
            break;
        default:
            break;
    }
    if (!snap.active) return;
    Sample s = sample_at(snap, event->timestamp);
    switch (event->type) {
        case ASENSOR_TYPE_ACCELEROMETER:
            write3(event->data, s.ax, s.ay, s.az);
            break;
        case ASENSOR_TYPE_ACCELEROMETER_UNCALIBRATED:
            write3(event->data, s.ax + s.bax, s.ay + s.bay, s.az + s.baz);
            event->data[3] = s.bax;
            event->data[4] = s.bay;
            event->data[5] = s.baz;
            break;
        case ASENSOR_TYPE_LINEAR_ACCELERATION:
            write3(event->data, s.lx, s.ly, s.lz);
            break;
        case ASENSOR_TYPE_GRAVITY:
            write3(event->data, s.gx, s.gy, s.gz);
            break;
        case ASENSOR_TYPE_GYROSCOPE:
            write3(event->data, s.wx, s.wy, s.wz);
            break;
        case ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED:
            write3(event->data, s.wx + s.bwx, s.wy + s.bwy, s.wz + s.bwz);
            event->data[3] = s.bwx;
            event->data[4] = s.bwy;
            event->data[5] = s.bwz;
            break;
        case ASENSOR_TYPE_ROTATION_VECTOR:
        case ASENSOR_TYPE_GAME_ROTATION_VECTOR:
            event->data[0] = s.qx;
            event->data[1] = s.qy;
            event->data[2] = s.qz;
            event->data[3] = s.qw;
            event->data[4] = 0.05f;
            break;
        case ASENSOR_TYPE_MAGNETIC_FIELD:
            write3(event->data, s.mx, s.my, s.mz);
            break;
        default:
            break;
    }
}

int64_t boot_ns() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int inject_steps(ASensorEvent *events, size_t room, const Snap &snap) {
    if (snap.java_owns || !snap.active || room == 0) return 0;
    if (g_handle_counter < 0 && g_handle_detector < 0) return 0;
    float base = 0.f;
    if (g_hw_valid.load(std::memory_order_acquire)) {
        uint32_t bits = g_hw_bits.load(std::memory_order_acquire);
        std::memcpy(&base, &bits, sizeof(base));
    }
    double fake = static_cast<double>(snap.counter) - static_cast<double>(base);
    if (fake < 1.0) return 0;
    auto target = static_cast<int64_t>(fake);
    int added = 0;
    int64_t now = boot_ns();
    int budget = 8;
    while (g_ndk_emitted < target && static_cast<size_t>(added) < room && budget-- > 0) {
        bool wrote = false;
        if (g_handle_detector >= 0 && static_cast<size_t>(added) < room) {
            ASensorEvent *ev = &events[added++];
            std::memset(ev, 0, sizeof(*ev));
            ev->version = g_event_version;
            ev->sensor = g_handle_detector;
            ev->type = ASENSOR_TYPE_STEP_DETECTOR;
            ev->data[0] = 1.f;
            int64_t ts = now - static_cast<int64_t>(budget) * 1000000LL;
            if (ts <= g_last_inject_ts) ts = g_last_inject_ts + 1000;
            ev->timestamp = ts;
            g_last_inject_ts = ts;
            wrote = true;
        }
        if (g_handle_counter >= 0 && static_cast<size_t>(added) < room) {
            ASensorEvent *ev = &events[added++];
            std::memset(ev, 0, sizeof(*ev));
            ev->version = g_event_version;
            ev->sensor = g_handle_counter;
            ev->type = ASENSOR_TYPE_STEP_COUNTER;
            float shown = monotonic_store(snap.counter);
            write_step(ev, monotonic_store(std::floor(shown + 0.5f)));
            int64_t ts = g_last_inject_ts + 1;
            ev->timestamp = ts;
            g_last_inject_ts = ts;
            wrote = true;
        }
        if (!wrote) break;
        g_ndk_emitted++;
    }
    return added;
}

void transform(ASensorEvent *events, size_t count) {
    Snap snap{};
    if (!read_snap(&snap)) return;
    for (size_t i = 0; i < count; i++) apply_event(&events[i], snap);
}

ssize_t hooked_read(void *self, ASensorEvent *events, size_t count) {
    if (g_in_hook || g_orig_read == nullptr) {
        return g_orig_read != nullptr ? g_orig_read(self, events, count) : -1;
    }
    g_in_hook++;
    ssize_t got = g_orig_read(self, events, count);
    if (got > 0 && events != nullptr) {
        transform(events, static_cast<size_t>(got));
        Snap snap{};
        if (read_snap(&snap)) {
            size_t room = count > static_cast<size_t>(got) ? count - static_cast<size_t>(got) : 0;
            int extra = inject_steps(events + got, room, snap);
            got += extra;
        }
    }
    g_in_hook--;
    return got;
}

int hooked_get_events(void *queue, ASensorEvent *events, size_t count) {
    if (g_in_hook || g_orig_get_events == nullptr) {
        return g_orig_get_events != nullptr ? g_orig_get_events(queue, events, count) : -1;
    }
    g_in_hook++;
    int got = g_orig_get_events(queue, events, count);
    if (got > 0 && events != nullptr) {
        transform(events, static_cast<size_t>(got));
        Snap snap{};
        if (read_snap(&snap)) {
            size_t room = count > static_cast<size_t>(got) ? count - static_cast<size_t>(got) : 0;
            int extra = inject_steps(events + got, room, snap);
            got += extra;
        }
    }
    g_in_hook--;
    return got;
}

bool hook_read_symbol(const char *lib, const char *sym) {
    void *orig = nullptr;
    void *stub = shadowhook_hook_sym_name(lib, sym, reinterpret_cast<void *>(hooked_read), &orig);
    if (stub == nullptr || orig == nullptr) return false;
    g_orig_read = reinterpret_cast<read_fn>(orig);
    __android_log_print(ANDROID_LOG_INFO, kTag, "hooked %s %s", lib, sym);
    return true;
}

bool install_hooks() {
    const char *syms64[] = {
            "_ZN7android16SensorEventQueue4readEP12ASensorEventm",
            "_ZN7android16SensorEventQueue4readEPNS_12ASensorEventEm",
    };
    const char *syms32[] = {
            "_ZN7android16SensorEventQueue4readEP12ASensorEventj",
            "_ZN7android16SensorEventQueue4readEPNS_12ASensorEventEj",
            "_ZN7android16SensorEventQueue4readEP12ASensorEventm",
    };
    const char *const *syms = sizeof(void *) == 8 ? syms64 : syms32;
    int n = sizeof(void *) == 8 ? 2 : 3;
    for (int i = 0; i < n; i++) {
        if (hook_read_symbol("libsensor.so", syms[i])) return true;
    }
    void *orig = nullptr;
    void *stub = shadowhook_hook_sym_name(
            "libandroid.so", "ASensorEventQueue_getEvents",
            reinterpret_cast<void *>(hooked_get_events), &orig);
    if (stub != nullptr && orig != nullptr) {
        g_orig_get_events = reinterpret_cast<get_events_fn>(orig);
        log_info("hooked ASensorEventQueue_getEvents");
        return true;
    }
    int err = shadowhook_get_errno();
    __android_log_print(ANDROID_LOG_INFO, kTag, "native sensor hook unavailable: %s",
                        shadowhook_to_errmsg(err));
    return false;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uy_li_runhook_NativeBridge_nativeInit(JNIEnv *, jclass) {
    int rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "shadowhook_init failed: %s", shadowhook_to_errmsg(rc));
        return JNI_FALSE;
    }
    return install_hooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uy_li_runhook_NativeBridge_nativePublish(
        JNIEnv *, jclass,
        jboolean active, jboolean spoof_pressure, jboolean java_owns,
        jfloat counter, jfloat step_hz, jdouble phase, jlong phase_time_ns,
        jfloat pressure, jfloat tilt_roll, jfloat tilt_pitch, jint noise_seed) {
    uint32_t seq = g_seq.load(std::memory_order_relaxed);
    uint32_t odd = (seq + 1u) | 1u;
    g_seq.store(odd, std::memory_order_release);
    g_snap.active = active ? 1 : 0;
    g_snap.spoof_pressure = spoof_pressure ? 1 : 0;
    g_snap.java_owns = java_owns ? 1 : 0;
    g_snap.counter = counter;
    g_snap.step_hz = step_hz;
    g_snap.phase = phase;
    g_snap.phase_time_ns = phase_time_ns;
    g_snap.pressure = pressure;
    g_snap.tilt_roll = tilt_roll;
    g_snap.tilt_pitch = tilt_pitch;
    g_snap.noise_seed = noise_seed;
    g_seq.store(odd + 1u, std::memory_order_release);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_uy_li_runhook_NativeBridge_nativeLastHardware(JNIEnv *, jclass) {
    if (!g_hw_valid.load(std::memory_order_acquire)) {
        return nanf("");
    }
    uint32_t bits = g_hw_bits.load(std::memory_order_acquire);
    float value = 0.f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_uy_li_runhook_NativeBridge_nativeMonotonic(JNIEnv *, jclass, jfloat proposed) {
    return monotonic_store(proposed);
}
