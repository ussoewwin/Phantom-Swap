# Phantom Swap

An Android app that uses external storage as virtual memory (swap file) without requiring root access, helping to reduce app force-closes and enhance multitasking performance.

## Features

- **No Root Required**: Create and manage swap files without root access
- **Up to 8GB Swap**: Flexible size configuration from 128MB to 8GB
- **Auto Restore**: Automatically restores swap after device reboot
- **Watchdog**: Monitors swap status and automatically responds to anomalies
- **Battery Optimization Bypass**: Keeps swap ACTIVE even when screen is off
- **Widget**: Control ON/OFF from home screen

## How It Works

An external storage area is secured as a swap file, and `mmap` allows the OS to recognize it as physical memory.
This increases effective memory capacity and reduces app force-closes by Low Memory Killer (LMK).

### Technical Details

- **1MB Block Writing**: Fast writing in 1MB units to avoid FUSE overhead
- **2GB Chunk Mapping**: Divided and mapped to avoid Java's 2GB limit
- **Foreground Service**: Continuous swap management in the background
- **Coroutine Support**: Asynchronous processing to prevent UI freezing

## Requirements

- Android 8.0 (API 26) or higher
- Non-rooted Android device
- 64-bit OS recommended (supports up to 8GB)

## Installation

1. Download the APK from [latest releases](https://github.com/ussoewwin/Phantom-Swap/releases)
2. Install on your device (enable "Install unknown apps")

## Usage

1. Launch the app
2. Select swap size with SeekBar (128MB to 8GB)
3. Tap "CREATE SWAP"
4. Wait for completion (may take several minutes for larger sizes)

## Notes

- Swap file creation requires available storage space equal to the selected size
- Large swap creation may take time
- Some devices (Samsung, etc.) may not work due to SELinux settings
- Battery optimization exclusion is recommended to reduce battery consumption

## Development

This project is developed in Kotlin.

### Build

```bash
./gradlew assembleDebug
```

### Development Plan

See [`PhantomSwap_Project_Master_Compendium.md`](PhantomSwap_Project_Master_Compendium.md) for detailed development plan.

## License

MIT License