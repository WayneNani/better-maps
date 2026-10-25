project(traffxml_tests)

set(SRC
  traff_e2e_tests.cpp
  traff_model_xml_tests.cpp
  traff_source_tests.cpp
  traff_test_support.cpp
  traff_test_support.hpp
)

omim_add_test(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    traffxml
    routing
    storage
    indexer
    traffic
    platform
)
