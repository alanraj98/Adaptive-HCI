@echo off
"C:\\Users\\deniz\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HC:\\Users\\deniz\\AndroidStudioProjects\\Adaptive-HCI\\Xamera\\OpenCV-4.10.0\\libcxx_helper" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=21" ^
  "-DANDROID_PLATFORM=android-21" ^
  "-DANDROID_ABI=x86" ^
  "-DCMAKE_ANDROID_ARCH_ABI=x86" ^
  "-DANDROID_NDK=C:\\Users\\deniz\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\deniz\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\deniz\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\deniz\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=C:\\Users\\deniz\\AndroidStudioProjects\\Adaptive-HCI\\Xamera\\OpenCV-4.10.0\\build\\intermediates\\cxx\\Debug\\2hk6r5k3\\obj\\x86" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=C:\\Users\\deniz\\AndroidStudioProjects\\Adaptive-HCI\\Xamera\\OpenCV-4.10.0\\build\\intermediates\\cxx\\Debug\\2hk6r5k3\\obj\\x86" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BC:\\Users\\deniz\\AndroidStudioProjects\\Adaptive-HCI\\Xamera\\OpenCV-4.10.0\\.cxx\\Debug\\2hk6r5k3\\x86" ^
  -GNinja ^
  "-DANDROID_STL=c++_shared"
