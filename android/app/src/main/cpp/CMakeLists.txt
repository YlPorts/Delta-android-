cmake_minimum_required(VERSION 3.22.1)
project(delta_android_bridge LANGUAGES CXX)
add_library(delta_android SHARED delta_android.cpp)
target_compile_features(delta_android PRIVATE cxx_std_17)
target_compile_options(delta_android PRIVATE -Wall -Wextra -Werror)
target_link_libraries(delta_android PRIVATE log dl)

target_link_options(delta_android PRIVATE "-Wl,-z,max-page-size=16384")
