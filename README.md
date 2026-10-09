# Tabby

**A focused spending companion for iPhone.**

Tabby makes everyday expense tracking feel immediate: log a spend in seconds, understand where your money is going, and keep shared balances clear without turning personal finance into a spreadsheet.

The experience is built around a dark, warm-gold visual system, expressive spending rings, and calm typography. Tabby is also available for Android on the [`android`](https://github.com/Maghizhan-05/Tabby/tree/android) branch.

## What you can do

- **Log spending quickly** with an amount, category, note, and date.
- **Explore your activity** across daily, weekly, monthly, yearly, category, and trend views.
- **Edit or remove entries** directly from recent activity.
- **Track balances with friends** using clear “they owe,” “you owe,” and net totals.
- **Create and manage categories** that fit the way you spend.
- **See spending at a glance** from configurable Home Screen widgets.
- **Open Quick Entry faster** with App Shortcuts and Back Tap.
- **Keep working offline** and optionally sync your data across devices with a Tabby account.

## Designed to stay out of the way

Tabby keeps the amount and the next action visually dominant. Analytics remain compact, Quick Entry stays focused, and the same gold-ring language connects the app, widgets, and confirmation surfaces.

## Widgets and system experiences

- Small and medium Home Screen widgets
- Configurable analytics modes
- Tap-to-open Quick Entry
- App Shortcut support
- Live Activity and Dynamic Island confirmation after logging a spend

## Build the iOS app

### Requirements

- Xcode 27 or newer
- iOS 17 or newer
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)

### Run locally

```bash
brew install xcodegen
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig
xcodegen generate
./run.sh
```

Cloud configuration is optional for a local build. Without it, Tabby continues to work as a local-first spending tracker.

## Technology

Tabby for iOS is built with SwiftUI, SwiftData, WidgetKit, ActivityKit, App Intents, Swift Charts, and Supabase.

## Repository branches

- [`main`](https://github.com/Maghizhan-05/Tabby/tree/main) — iOS
- [`android`](https://github.com/Maghizhan-05/Tabby/tree/android) — Android
