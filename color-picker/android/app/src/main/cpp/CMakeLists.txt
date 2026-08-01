cmake_minimum_required(VERSION 3.22.1)
project("ofcolorpicker")

add_library(ofcolorpicker SHARED
    jni_bridge.cpp
    swirl.cpp
    search.cpp
    texture.cpp
)

target_include_directories(ofcolorpicker PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(ofcolorpicker PRIVATE
    -O2 -fno-exceptions -fno-rtti
    -Wall -Wextra -Wno-unused-parameter
)

# Link android JNI / log
find_library(log-lib log)
target_link_libraries(ofcolorpicker ${log-lib})
