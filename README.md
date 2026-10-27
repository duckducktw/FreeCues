# FreeCues

Free, ad-free vehicle motion cues for Android — animated dots at the screen edges that move with the car, to reduce motion sickness while reading in a vehicle (like iOS 18 *Vehicle Motion Cues*).

免費、無廣告的 Android 暈車輔助點：螢幕邊緣的小點會跟著車子的加減速、轉彎移動，讓眼睛看到的和身體感受到的一致，減少看手機暈車。

- No ads, no tracking, **no INTERNET permission**, ~30 KB APK
- Grid / Bubble styles, White / Black / custom color, 3 sizes, 2 sensitivities
- "Only in Vehicle" mode: dots appear when moving and fade out (one by one, iOS-style) when stopped
- Orientation-independent: forward/back works whether the phone is flat, tilted or upright
- Quick Settings tile + notification stop button

## Install

Download `FreeCues.apk` from [Releases](../../releases), allow "Display over other apps" when prompted.

## How it works

1. Rotation vector → rotation matrix; linear acceleration is transformed into the world frame, and its horizontal component is projected onto the vehicle's forward / lateral axes.
2. Soft dead-zone + low-pass filter (no hard threshold → small motions still register, no "stuck" dots).
3. Acceleration drives a damped velocity; dots are integrated per frame (Choreographer) for smooth motion.
4. Yaw rate adds lateral motion while turning.

See [`docs/SPEC.md`](docs/SPEC.md) for the behavioural spec.

## Build

No Gradle. Requires Android SDK `build-tools;35.0.0` and `platforms;android-35`, JDK 17+.

```sh
./build.sh                 # → FreeCues.apk (debug-signed with a local key)
# unit tests (pure JVM)
mkdir -p build/test && javac -d build/test src/tw/roastduck/freecues/MotionProcessor.java test/tw/roastduck/freecues/MotionProcessorTest.java \
  && java -cp build/test tw.roastduck.freecues.MotionProcessorTest
```

## Clean-room notice

This is an independent implementation written from a behavioural specification. It contains no code, images or other assets from any third-party app.

## License

MIT
