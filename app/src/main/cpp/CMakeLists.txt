cmake_minimum_required(VERSION 3.22)
project(hdguard LANGUAGES C)

# 极小的守卫库：只提供插件密钥派生因子的运行时供给。
# 不依赖任何第三方库，编译产物约 10KB。
add_library(hdguard SHARED hdguard.c)
target_link_libraries(hdguard PRIVATE log)

# 默认开启「调用方证书摘要校验」。本机自改包（debug 签名、核心破解覆盖安装）
# 摘要必然对不上，可在构建时传 -DHD_GUARD_SKIP_CERT=ON 编译期去掉这道门。
option(HD_GUARD_SKIP_CERT "skip caller cert digest check (local self-built only)" OFF)
if(HD_GUARD_SKIP_CERT)
    target_compile_definitions(hdguard PRIVATE HD_GUARD_SKIP_CERT=1)
    message(STATUS "hdguard: caller cert check DISABLED")
endif()
