project(crowd_traffic_tests)

set(SRC
  client_tests.cpp
  daily_limits_tests.cpp
  e2e_client.cpp
  go_vectors.hpp
  protocol_tests.cpp
  randomness_tests.cpp
  segmenter_tests.cpp
  test_support.cpp
  test_support.hpp
  tiles_tests.cpp
)

omim_add_test(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    crowd_traffic
    platform
)
