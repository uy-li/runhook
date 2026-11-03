cmake_minimum_required(VERSION 3.22.1)
project(runhook)

add_library(runhook SHARED runhook_native.cpp)

find_library(log-lib log)
find_package(shadowhook REQUIRED CONFIG)

target_compile_features(runhook PRIVATE cxx_std_17)
target_link_libraries(runhook shadowhook::shadowhook ${log-lib})
target_compile_options(runhook PRIVATE -Wall -Wno-unused-parameter -fno-exceptions -fno-rtti)
