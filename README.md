# 📙 KNCR

> Kotlin/Native CInterop Repository (KNCR)

## Why?

Kotlin/Native CInterop allows you to integrate C projects into your Kotlin/Native projects, assuming you either have a header-only library or
have the binaries available at your disposal. This projects solves these issues by providing a way to generate the Klib for you, so you can use it in your Kotlin/Native projects.

## Getting Started

The repository lists a series of popular and useful C libraries in [`repositories.json`](src/main/resources/repositories.json) that are converted into Kotlin/Native library formats (`*.klib`)
for Windows (MingwX64), macOS x64/Arm64, and Linux x64.

You can use the libraries in your Kotlin/Native projects by adding the following to your `build.gradle.kts` file:

```kotlin
repositories {
    maven("https://repo.calcugames.xyz/repository/kncr/")
}

dependencies {
    implementation("com.github.myproject:native-mingwx64:1.0.0@klib")
}
```

## Contributing

If you want to add a new library, please create a pull request. Here are the schematics for the repository file:
- `handle` - **Required.** The GitHub Repository handle to use. For example, this repository's handle is `CalculusGames/KNCR`.
- `type` - **Required.** The Build System to use. Currently, only `cmake` is supported, but we also provide a `custom` build type where you can manually compile C files into a binary and then a static library.
- `name` - The name of the library. This is used to generate the Klib and Artifact ID. If not set, defaults to the repository name specified in the handle.
- `headers` - The name of the root directory where header files (`*.h`) are stored. Defaults to `include`.
- `headerFilter` - An optional regex expression to filter out the file contents of `headers`.
- `include-c` - Whether to include C source files (`*.c`) in the cinterop process. Defaults to `false`.
- `definition_extra` - Custom definition declarations to pass to the generated `.def` as specified in the [Kotlin Documentation](https://kotlinlang.org/docs/native-definition-file.html#add-custom-declarations).

The `extra` object contains repository-specific information that could be useful.

- `extra.config-flags` and `extra.build-flags` can be extra flags passed to the CMake or other build compilers.
- If you have `type` set to `custom`, specify your build commands in `extra.build-cmds` separated by a newline character (`\n`).
