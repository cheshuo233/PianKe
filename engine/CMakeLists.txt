cmake_minimum_required(VERSION 3.22)
project(film_engine C)

add_library(film_engine STATIC
    src/catalog.c
    src/date_stamp.c
    src/develop.c
    src/grain.c
    src/halation.c
    src/lut.c)
target_include_directories(film_engine PUBLIC include)
set_target_properties(film_engine PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
if(MSVC)
    target_compile_options(film_engine PRIVATE /W4 /WX)
else()
    target_compile_options(film_engine PRIVATE -Wall -Wextra -Werror)
endif()
