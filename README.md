[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.8.10-green.svg)]()
[![Version](https://img.shields.io/badge/Release-v1.0.0-green.svg)]()


# Overview

BlocToMviKotlinPlugin is an Android Studio plugin to migrate Bloc classes to the MVIKotlin equivalent.

## Installation

- **Installing manually:**
    - Download the plugin package on [GitHub Releases](https://github.com/LStudioO/BlocToMviKotlinPlugin/releases) or build it locally.
    - <kbd>Preferences(Settings)</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd> >
      Select the plugin package and install.

Restart the **IDE** after installation.

## Using

1. Navigate to a Bloc class in Android Studio
2. Open the refactor menu and click on BlocToMVIKotlin or use the default shortcut:

    - Windows - <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>Alt</kbd> + <kbd>B</kbd>
    - Mac OS - <kbd>Control</kbd> + <kbd>Option</kbd> + <kbd>Command</kbd> + <kbd>B</kbd>

## Features

- Adds an MVIKotlin gradle dependency if it doesn't exist
- Generates a Store interface and implementation
- Replace the Bloc class with the new Store implementation
    - Changes the Bloc class references
    - Replaces the Bloc class functions with the corresponding Store.accept(Intent)
    - Replaces Bloc.asFlow with Store.states
    - Replaces Bloc.currentState with Store.state
    - Removes interface delegation and implement methods
    - Replaces the dependencies in a Koin module
- Removes unused imports in the affected files
- Formats the changed code

## License

**BlocToMviKotlinPlugin** is distributed under the terms of the Apache License (Version 2.0). See the
[license](LICENSE) for more information.
