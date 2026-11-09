project(ev_planning)

# better-maps: charging planner for electric vehicles.
set(SRC
  candidates.cpp
  candidates.hpp
  charger_status.cpp
  charger_status.hpp
  charging.cpp
  charging.hpp
  detours.cpp
  detours.hpp
  energy_model.cpp
  energy_model.hpp
  plan_json.cpp
  plan_json.hpp
  planner.cpp
  planner.hpp
  route_points.cpp
  route_points.hpp
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
    routing
    search
  PRIVATE
    glaze::glaze
)

omim_add_test_subdirectory(ev_planning_tests)
omim_add_test_subdirectory(ev_planning_integration_tests)
omim_add_tool_subdirectory(ev_planning_tool)
