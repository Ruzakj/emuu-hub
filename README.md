# Emu Hub

Emu Hub is an Android/Kotlin launcher and library focused on making multiple emulator workflows easier to manage from one place.

## Project goals

- Keep ROM discovery and launching simple and predictable.
- Route supported systems to the appropriate emulator without breaking existing user flows.
- Provide a consistent touch-controller experience where Emu Hub owns the controls.
- Keep the application responsive and lightweight on Android devices.
- Ship changes in small, build-verified iterations.

## Current development areas

- ROM library and direct-launch routing
- Emulator integration and compatibility
- Unified touch-controller UI
- Controller size and opacity customization
- Android UI/UX and stability
- In-app update delivery

## Build

The project uses Gradle Kotlin DSL. GitHub Actions is the canonical CI check for repository changes.

A change should not be considered complete until the Android APK workflow passes. When CI fails, fix the failing build before starting an unrelated feature change.

## Repository layout

- `app/` — Android application module
- `.github/workflows/` — CI/build and update workflows
- `DIRECT_ROM_POLICY.md` — ROM direct-launch/routing policy
- `build.gradle.kts` — root Gradle configuration
- `settings.gradle.kts` — project/module configuration

## Development policy

Prefer incremental, recoverable changes. Avoid no-op commits solely to trigger builds unless a manual retrigger is genuinely required. Preserve working emulator routes and existing user functionality while improving the project.
