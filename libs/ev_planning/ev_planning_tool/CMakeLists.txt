project(ev_planning_tool)

set(SRC ev_planning_tool.cpp)

omim_add_executable(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    ev_planning
    geometry
    indexer
    platform
    routes_builder
    routing
    search
    search_quality
    search_tests_support
    storage
    traffic
    gflags::gflags
)
