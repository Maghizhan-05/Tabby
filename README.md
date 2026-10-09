# Tabby for Android

**A modern, focused spending companion—built natively for Android.**

Tabby makes it quick to record everyday spending, understand patterns, and keep balances with friends clear. The Android experience carries the same dark, warm-gold identity as Tabby for iPhone while using native Android navigation, widgets, gestures, and system integrations.

## What you can do

- **Log a spend quickly** with an amount, category, note, and date.
- **Explore spending** across daily, weekly, monthly, yearly, category, and trend views.
- **Swipe to edit or delete** recent activity with responsive motion and haptics.
- **Track balances with friends** through clear debt and net summaries.
- **Manage personal categories** for the way you spend.
- **Use responsive Home Screen widgets** that adapt from a compact ring to a detailed breakdown.
- **Customize each widget** independently and tap it to open Quick Entry.
- **Work offline first** and optionally sync across devices with a Tabby account.

## A cohesive Android experience

Tabby uses a connected Home/Friends toggle, Inter and Nunito typography, category-composition rings, layered charcoal surfaces, and restrained gold accents. The goal is not to imitate iOS controls—it is to preserve the same product identity with interactions that feel at home on Android.

## Widgets

Tabby widgets adapt to the space provided by the launcher:

- Compact layouts emphasize the spending ring and current amount.
- Wider layouts add a category breakdown.
- The header cycles the displayed period.
- Tapping the widget body opens Log a Spend.
- Widget preferences are stored per widget instance.

## Build the Android app

### Requirements

- JDK 17
- Android SDK 36
- Android Studio or the Android command-line tools

### Build and test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
./gradlew clean assembleDebug test
```

### Install on an emulator or device

```bash
./gradlew installDebug
adb shell am start -n com.maghizhan.tabby/.MainActivity
```

Cloud configuration is optional for a local build. Without it, Tabby remains available as a local-first spending tracker.

## Technology

Tabby for Android is built with Kotlin, Jetpack Compose, Room, Glance, Material 3, and Supabase.

## Repository branches

- [`android`](https://github.com/Maghizhan-05/Tabby/tree/android) — Android
- [`main`](https://github.com/Maghizhan-05/Tabby/tree/main) — iOS

Bundled Inter and Nunito fonts are distributed under the SIL Open Font License; license texts are included in [`licenses/`](licenses/).
