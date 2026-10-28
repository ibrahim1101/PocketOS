cmake_minimum_required(VERSION 3.22.1)
project(pocketos_vm_bridge LANGUAGES CXX)

add_library(pocketos_vm SHARED pocketos_vm.cpp)
target_compile_features(pocketos_vm PRIVATE cxx_std_17)
target_compile_options(pocketos_vm PRIVATE -Wall -Wextra -Werror)
