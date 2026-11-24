cmake_minimum_required(VERSION 3.22.1)
project(uvciso C)
add_library(uvciso SHARED uvc_iso.c)
target_link_libraries(uvciso log)
