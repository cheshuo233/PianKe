cmake_minimum_required(VERSION 3.22)
project(pianke C)

add_subdirectory(../../../../engine film_engine)

add_library(pianke SHARED pianke_jni.c)
set_target_properties(pianke PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
target_compile_options(pianke PRIVATE -Wall -Wextra -Werror)
target_link_libraries(pianke PRIVATE film_engine jnigraphics android)
