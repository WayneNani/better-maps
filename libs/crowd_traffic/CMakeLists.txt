project(crowd_traffic)

set(SRC
  client.cpp
  client.hpp
  daily_limits.cpp
  daily_limits.hpp
  protocol.cpp
  protocol.hpp
  randomness.cpp
  randomness.hpp
  segmenter.cpp
  segmenter.hpp
  tiles.cpp
  tiles.hpp
)

omim_add_library(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  monocypher
)

# The segment keys are computed from floating-point geometry and must match the Go reference
# implementation (and other clients) as closely as possible: no -ffast-math reassociation.
target_compile_options(${PROJECT_NAME} PRIVATE -fno-fast-math)

# Line coverage of the core logic (gcov), e.g.
#   cmake -DCROWD_TRAFFIC_COVERAGE=ON build/desktop && tools/better-maps/bm.sh cpp-test crowd_traffic_tests
option(CROWD_TRAFFIC_COVERAGE "Instrument libs/crowd_traffic for gcov" OFF)
if (CROWD_TRAFFIC_COVERAGE)
  target_compile_options(${PROJECT_NAME} PRIVATE -O0 --coverage)
  target_link_options(${PROJECT_NAME} PUBLIC --coverage)
endif()

omim_add_test_subdirectory(crowd_traffic_tests)
