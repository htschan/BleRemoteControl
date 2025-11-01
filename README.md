# BleRemoteControl

## Overview

BleRemoteControl is an Android application designed to control a remote device securely over Bluetooth Low Energy (BLE). The app uses a shared secret (a UUID) for HMAC-based authentication to ensure that only authorized users can issue commands.

## Features

-   **BLE Communication:** Communicates with peripheral devices over BLE.
-   **Secure Control:** Implements an HMAC-based challenge-response mechanism to secure commands.
-   **QR Code Provisioning:** Easily provision the shared secret by scanning a QR code.
-   **QR Code Generation:** Generate and share a QR code from a secret UUID within the app.
-   **Triple-Tap Protection:** Control buttons require three quick taps to prevent accidental activation.
-   **Connection Status:** Provides real-time feedback on the BLE connection status.

## Technical Details

### Communication

The app utilizes the Android BLE framework to scan for, connect to, and communicate with a target peripheral. The `BleManager` class encapsulates the core BLE logic, handling connection state, service discovery, and characteristic read/writes.

### Security

Authentication is based on a shared secret (a UUID) that must be present on both the Android app and the peripheral device. The app uses this secret to sign commands with an HMAC signature. The `SecureHmacStore` class is responsible for securely storing this secret on the device.

### Provisioning

-   **Scanning:** The app can use the device's camera to scan a QR code containing the secret UUID. The `ScanQrActivity` handles this process.
-   **Generation:** The `ProvisionQrActivity` allows a user to input a UUID and generate a corresponding QR code, which can then be shared or scanned by another device.

### User Interface

-   `MainActivity`: The main screen of the app. It displays the connection status, provides "Open" and "Close" buttons for controlling the remote device, and gives feedback on the command process.
-   `ProvisionQrActivity`: A screen dedicated to managing the secret. It allows generating a new QR code from a UUID.

### Permissions

The app requires the following permissions to function correctly:
-   `BLUETOOTH_SCAN`: To discover nearby BLE devices.
-   `BLUETOOTH_CONNECT`: To connect to BLE devices.
-   `BLUETOOTH_ADVERTISE`: (If applicable for any advertising features).
-   `ACCESS_FINE_LOCATION`: Required for BLE scanning on Android versions prior to S (API 31).
-   `CAMERA`: To scan QR codes for provisioning.

## Key Classes

-   `MainActivity`: Manages the main UI, user interactions, and integrates the `BleManager`.
-   `ProvisionQrActivity`: Handles the generation of QR codes from a secret UUID.
-   `BleManager`: A central class for managing all BLE-related operations, including scanning, connecting, and data transfer.
-   `SecureHmacStore`: A utility for securely persisting the shared HMAC secret on the Android device.
-   `TripleTapGuard`: A helper class that adds triple-tap protection to buttons to prevent accidental command execution.
