# BondWise UG Android MVP

A native Android Studio starter based on the BondWise design prototype. It includes an overview, a bond comparison watchlist, an estimate calculator, and a local-session bond list.

## Run

Open this folder in Android Studio and let Gradle sync. The project uses Kotlin, Jetpack Compose, Android Gradle Plugin 8.7.3, and compile SDK 35.

## Yield assumptions

Estimated YTM is solved as the periodic internal rate of return for regular coupon periods, redemption at par, and purchase price excluding accrued interest. The calculation does not model real settlement dates, odd first/last coupon periods, day-count rules, or taxes/fees. The sample bond figures are illustrative and no market data is connected.

## Current scope

The add-bond list is held in memory for the current app session. Portfolio persistence, market feeds, exact dated cash-flow conventions, export, and production accessibility/localization remain future work.
