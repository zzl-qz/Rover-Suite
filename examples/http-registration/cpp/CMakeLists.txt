cmake_minimum_required(VERSION 3.16)
project(rover_http_registrar_example LANGUAGES CXX)

find_package(CURL REQUIRED)
find_package(Threads REQUIRED)

add_executable(rover_http_registrar_example example.cpp)
target_compile_features(rover_http_registrar_example PRIVATE cxx_std_20)
target_link_libraries(rover_http_registrar_example PRIVATE CURL::libcurl Threads::Threads)
