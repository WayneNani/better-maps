project(routing_perf_tool)

set(SRC routing_perf_tool.cpp)

omim_add_executable(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    coding
    geometry
    indexer
    platform
    routing
    routing_common
    storage
    traffic
    gflags::gflags
)
