project(ev_planning_tests)

set(SRC
  charging_tests.cpp
  energy_model_tests.cpp
  plan_json_tests.cpp
  planner_tests.cpp
  station_tests.cpp
  test_helpers.cpp
  test_helpers.hpp
  vehicle_profile_tests.cpp
)

omim_add_test(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    ev_planning
    geometry
    indexer
    platform
)
