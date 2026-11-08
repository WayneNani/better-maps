project(route_search_tool)

set(SRC route_search_tool.cpp)

omim_add_executable(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    geometry
    indexer
    platform
    routes_builder
    routing
    search
    search_quality
    search_tests_support
    gflags::gflags
)
