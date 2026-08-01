// PNG texture loading via stb_image. Produces an RGBA8 buffer,
// row-major, top-origin (row 0 == top), exactly like the numpy array the Python
// code indexes.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct Texture {
    int w = 0, h = 0;
    std::vector<uint8_t> rgba;  // size h*w*4, row-major, top-origin
    bool ok() const { return w > 0 && h > 0 && rgba.size() == (size_t)w * h * 4; }
};

bool load_texture(const std::string& path, Texture& out);

// Load from memory buffer (for Android assets)
bool load_texture_mem(const uint8_t* data, size_t len, Texture& out);
