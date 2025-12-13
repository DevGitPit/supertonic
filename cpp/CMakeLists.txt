cmake_minimum_required(VERSION 3.15)
project(Supertonic_CPP)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable aggressive optimization
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Add optimization flags
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG -ffast-math")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG -ffast-math")

# Find required packages
find_package(PkgConfig REQUIRED)
find_package(OpenMP)

# ONNX Runtime - Try multiple methods
# Method 1: Try to find via CMake config
find_package(onnxruntime QUIET CONFIG)

if(NOT onnxruntime_FOUND)
    # Method 2: Try pkg-config
    pkg_check_modules(ONNXRUNTIME QUIET libonnxruntime)
    
    if(ONNXRUNTIME_FOUND)
        set(ONNXRUNTIME_INCLUDE_DIR ${ONNXRUNTIME_INCLUDE_DIRS})
        set(ONNXRUNTIME_LIB ${ONNXRUNTIME_LIBRARIES})
    else()
        # Method 3: Manual search in common locations
        find_path(ONNXRUNTIME_INCLUDE_DIR 
            NAMES onnxruntime_cxx_api.h
            PATHS
                /usr/local/include
                /opt/homebrew/include
                /usr/include
                ${CMAKE_PREFIX_PATH}/include
            PATH_SUFFIXES onnxruntime
        )
        
        find_library(ONNXRUNTIME_LIB
            NAMES onnxruntime libonnxruntime
            PATHS
                /usr/local/lib
                /opt/homebrew/lib
                /usr/lib
                ${CMAKE_PREFIX_PATH}/lib
        )
    endif()
    
    if(NOT ONNXRUNTIME_INCLUDE_DIR OR NOT ONNXRUNTIME_LIB)
        message(FATAL_ERROR "ONNX Runtime not found. Please install it:\n"
                            "  macOS: brew install onnxruntime\n"
                            "  Ubuntu: See README.md for installation instructions")
    endif()
    
    message(STATUS "Found ONNX Runtime:")
    message(STATUS "  Include: ${ONNXRUNTIME_INCLUDE_DIR}")
    message(STATUS "  Library: ${ONNXRUNTIME_LIB}")
endif()

# nlohmann/json
find_package(nlohmann_json REQUIRED)

# Include directories
if(NOT onnxruntime_FOUND)
    include_directories(${ONNXRUNTIME_INCLUDE_DIR})
endif()

# Helper library
add_library(tts_helper STATIC
    helper.cpp
    helper.h
)

if(onnxruntime_FOUND)
    target_link_libraries(tts_helper
        onnxruntime::onnxruntime
        nlohmann_json::nlohmann_json
    )
else()
    target_include_directories(tts_helper PUBLIC ${ONNXRUNTIME_INCLUDE_DIR})
    target_link_libraries(tts_helper
        ${ONNXRUNTIME_LIB}
        nlohmann_json::nlohmann_json
    )
endif()

# Enable OpenMP if available
if(OpenMP_CXX_FOUND)
    target_link_libraries(tts_helper OpenMP::OpenMP_CXX)
    message(STATUS "OpenMP enabled for parallel processing")
else()
    message(WARNING "OpenMP not found - parallel processing will be disabled")
endif()

# Example executable
add_executable(example_onnx
    example_onnx.cpp
)

if(onnxruntime_FOUND)
    target_link_libraries(example_onnx
        tts_helper
        onnxruntime::onnxruntime
        nlohmann_json::nlohmann_json
    )
else()
    target_link_libraries(example_onnx
        tts_helper
        ${ONNXRUNTIME_LIB}
        nlohmann_json::nlohmann_json
    )
endif()

# Native Messaging Host
add_executable(native_host
    native_host.cpp
)

if(onnxruntime_FOUND)
    target_link_libraries(native_host
        tts_helper
        onnxruntime::onnxruntime
        nlohmann_json::nlohmann_json
    )
else()
    target_link_libraries(native_host
        tts_helper
        ${ONNXRUNTIME_LIB}
        nlohmann_json::nlohmann_json
    )
endif()

# Installation
install(TARGETS example_onnx native_host DESTINATION bin)
install(TARGETS tts_helper DESTINATION lib)
install(FILES helper.h DESTINATION include)

