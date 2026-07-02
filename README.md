# Niimprint Android

Android library for printing bitmap images on NIIMBOT-compatible Bluetooth Classic printers.

## Modules

- `niimprint` - reusable Android library.
- `app` - small sample app that imports `niimprint` and prints `test_label_80x50.png`.

## Public API

The intended external surface is:

- `NiimprintPrinter` - connects to a paired Bluetooth printer, prints a bitmap, and closes the connection.
- `PrintOptions` - controls print density, target bitmap size, alignment, and rotation.
- `HorizontalAlignment`, `VerticalAlignment`, `ImageRotation` - option enums.
- `PrinterClient`, `NiimbotClassicManager`, and `NiimbotTransport` - lower-level API for advanced integration.

Protocol internals such as `RequestCode` are kept internal.

## Import as a module

```kotlin
dependencies {
    implementation(project(":niimprint"))
}
```

## Publish locally

```powershell
.\gradlew.bat :niimprint:publishToMavenLocal
```

Then another project can use:

```kotlin
dependencies {
    implementation("com.niimprint:niimprint:0.1.0")
}
```

## Basic usage

The app must request runtime Bluetooth permissions before connecting.

```kotlin
val printer = NiimprintPrinter(device)

try {
    printer.connect()
    printer.printImage(
        bitmap,
        PrintOptions(
            density = 5,
            targetWidth = 320,
            targetHeight = 320,
            horizontalAlignment = HorizontalAlignment.CENTER,
            verticalAlignment = VerticalAlignment.CENTER,
            rotation = ImageRotation.ROTATE_90
        )
    )
} finally {
    printer.close()
}
```

`targetWidth` and `targetHeight` define the prepared print canvas. The source bitmap is scaled to fit that canvas, then placed according to the selected horizontal and vertical alignment.
