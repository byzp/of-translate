#include "search.hpp"

#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

namespace {

double round4(double x) { return std::nearbyint(x * 10000.0) / 10000.0; }

constexpr double kStep = 0.001;
constexpr int kColorCount = 5;

// CIE76 fast path with sRGB LUT
struct SrgbLut {
    double v[256];
    SrgbLut() {
        for (int i = 0; i < 256; ++i) {
            double c = i / 255.0;
            v[i] = c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
        }
    }
};
const SrgbLut& srgb() {
    static SrgbLut lut;
    return lut;
}

struct Lab {
    double L, a, b;
};

inline double labf(double t) {
    double d = 6.0 / 29.0;
    return t > d * d * d ? std::pow(t, 1.0 / 3.0) : t / (3.0 * d * d) + 4.0 / 29.0;
}

Lab rgb_to_lab(int r, int g, int b) {
    const SrgbLut& s = srgb();
    double lr = s.v[r], lg = s.v[g], lb = s.v[b];
    double x = lr * 0.4124564 + lg * 0.3575761 + lb * 0.1804375;
    double y = lr * 0.2126729 + lg * 0.7151522 + lb * 0.0721750;
    double z = lr * 0.0193339 + lg * 0.1191920 + lb * 0.9503041;
    const double xn = 0.95047, yn = 1.0, zn = 1.08883;
    double fx = labf(x / xn), fy = labf(y / yn), fz = labf(z / zn);
    return {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
}

inline double sim_to(const Lab& t, const Lab& c) {
    double d = std::sqrt((t.L - c.L) * (t.L - c.L) + (t.a - c.a) * (t.a - c.a) +
                          (t.b - c.b) * (t.b - c.b));
    double s = 1.0 - d / 100.0;
    return s < 0.0 ? 0.0 : s;
}

struct Local {
    bool have = false;
    double sim = -1.0;
    float uvy = 0.0f;
    int slot = 0;
    RGBA colors[kColorCount];
};

}  // namespace

bool search(const std::string& target_hex_stripped, uint32_t picture_id,
            const std::vector<double>& params, const Texture* tex, Result& out) {
    if (!tex || !tex->ok()) return false;

    int tr, tg, tb;
    std::tie(tr, tg, tb) = hex_to_rgb(target_hex_stripped);
    const Lab target = rgb_to_lab(tr, tg, tb);

    SwirlNoiseGenHelper helper;
    helper.set_swirl_params(params, *tex);

    const int num = (int)std::lround(1.0 / kStep) + 1;  // 1001
    const double step = 1.0 / (double)(num - 1);

    auto scan = [&](int lo, int hi, Local& L) {
        RGBA buf[kColorCount];
        for (int i = lo; i < hi; ++i) {
            double yd = (i == num - 1) ? 1.0 : (double)i * step;
            float uvy = (float)yd;
            helper.get_colors(uvy, kColorCount, buf);
            for (int idx = 0; idx < kColorCount; ++idx) {
                double sim = sim_to(target,
                                    rgb_to_lab(buf[idx].r, buf[idx].g, buf[idx].b));
                if (sim > L.sim) {
                    L.sim = sim;
                    L.uvy = uvy;
                    L.slot = idx + 1;
                    for (int c = 0; c < kColorCount; ++c) L.colors[c] = buf[c];
                    L.have = true;
                }
            }
        }
    };

    unsigned hc = std::thread::hardware_concurrency();
    int T = hc == 0 ? 4 : (int)hc;
    if (T > 8) T = 8;
    if (T < 1) T = 1;

    std::vector<Local> locals(T);
    if (T == 1) {
        scan(0, num, locals[0]);
    } else {
        std::vector<std::thread> threads;
        threads.reserve(T);
        for (int t = 0; t < T; ++t) {
            int lo = (int)((long long)t * num / T);
            int hi = (int)((long long)(t + 1) * num / T);
            threads.emplace_back([&, lo, hi, t]() { scan(lo, hi, locals[t]); });
        }
        for (auto& th : threads) th.join();
    }

    double best_sim = -1.0;
    const Local* best = nullptr;
    for (int t = 0; t < T; ++t)
        if (locals[t].have && locals[t].sim > best_sim) {
            best_sim = locals[t].sim;
            best = &locals[t];
        }
    if (!best) return false;

    out.hex = rgb_to_hex(best->colors[best->slot - 1].r,
                          best->colors[best->slot - 1].g,
                          best->colors[best->slot - 1].b);
    out.sim = round4(best->sim);
    out.uvy = round4((double)best->uvy);
    out.slot = best->slot;
    for (int c = 0; c < kColorCount; ++c) out.colors[c] = best->colors[c];
    out.target_hex = "#" + target_hex_stripped;
    return true;
}
