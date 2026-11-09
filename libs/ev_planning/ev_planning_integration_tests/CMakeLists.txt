project(ev_planning_integration_tests)

set(SRC
  detours_integration_test.cpp
  ev_planning_integration_test.cpp
)

omim_add_test(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    coding
    ev_planning
    generator
    generator_tests_support
    geometry
    indexer
    platform
    platform_tests_support
    routing
    routing_common
    search
    search_tests_support
    storage
    traffic
)
