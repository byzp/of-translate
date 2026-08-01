#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include "stb_image.h"

#include "texture.hpp"

bool load_texture(const std::string& path, Texture& out) {
    int w = 0, h = 0, n = 0;
    unsigned char* data = stbi_load(path.c_str(), &w, &h, &n, 4);
    if (!data) return false;
    out.w = w;
    out.h = h;
    out.rgba.assign(data, data + (size_t)w * h * 4);
    stbi_image_free(data);
    return out.ok();
}

bool load_texture_mem(const uint8_t* data, size_t len, Texture& out) {
    int w = 0, h = 0, n = 0;
    unsigned char* pixels = stbi_load_from_memory(data, (int)len, &w, &h, &n, 4);
    if (!pixels) return false;
    out.w = w;
    out.h = h;
    out.rgba.assign(pixels, pixels + (size_t)w * h * 4);
    stbi_image_free(pixels);
    return out.ok();
}
