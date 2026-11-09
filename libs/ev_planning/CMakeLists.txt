project(ev_planning)

# better-maps: charging planner for electric vehicles.
set(SRC
  charger_status.cpp
  charger_status.hpp
  charging.cpp
  charging.hpp
  energy_model.cpp
  energy_model.hpp
  plan_json.cpp
  plan_json.hpp
  planner.cpp
  planner.hpp
  station.cpp
  station.hpp
  vehicle_profile.cpp
  vehicle_profile.hpp
)

omim_add_library(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PUBLIC
    base
    geometry
    indexer
    search
  PRIVATE
    glaze::glaze
)

omim_add_test_subdirectory(ev_planning_tests)
