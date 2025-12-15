cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

# Явно указываем все исходные файлы byedpi (исключая win_service.c для Android)
set(BYE_DPI_SRC
    byedpi/conev.c
    byedpi/desync.c
    byedpi/extend.c
    byedpi/main.c
    byedpi/mpool.c
    byedpi/packets.c
    byedpi/proxy.c
)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log)
