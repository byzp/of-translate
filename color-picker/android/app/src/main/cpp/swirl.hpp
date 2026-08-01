// Swirl-noise color generator + color math, ported from algo.py.
// The float32 path is preserved exactly so output matches the Python reference.
#pragma once
#include <cstdint>
#include <string>
#include <tuple>
#include <vector>

#include "texture.hpp"

struct RGBA {
    int r, g, b, a;
};

class SwirlNoiseGenHelper {
public:
    void set_swirl_params(const std::vector<double>& params, const Texture& tex);

    std::vector<RGBA> get_color_array(float uv_y, int count) const;
    void get_colors(float uv_y, int count, RGBA* out) const;

    bool configured() const { return configured_; }

private:
    bool configured_ = false;
    float swirl_[16][4] = {};
    std::vector<uint8_t> tex_;
    int tex_w_ = 0, tex_h_ = 0;

    float rotate_coef_ = 5.0f;
    float radius_coef_ = 0.5f;
    float color_power_y1_ = 0.2f;
    float color_power_y0_ = 1.5f;
};

// Color helpers
std::tuple<int, int, int> hex_to_rgb(const std::string& h);
std::string rgb_to_hex(int r, int g, int b);
double cie76(int ar, int ag, int ab, int br, int bg, int bb);
