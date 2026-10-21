# BondWise UG Android app

BondWise is a standalone Android app in this repository. It starts with an empty list and saves bonds locally on the device. Add a bond with its face value, coupon rate, clean purchase price, maturity date, next coupon date, frequency, and withholding tax rate (default 10%).

The app formats face value and purchase price with thousands separators as you type, and displays UGX amounts with grouped digits. Quick Compare lets you enter two temporary bonds and compare coupon rate, estimated yields and coupon income without saving them; results identify each bond by its column and entered name. Saved-bond comparison remains available separately. The app calculates gross and after-tax current yield and dated-cash-flow YTM estimates, and generates future coupon rows with gross coupon, estimated tax, net coupon, and principal redemption. Coupon tax is applied only to interest. If maturity does not coincide with a scheduled coupon date, the schedule shows principal at maturity but does not estimate a stub coupon. A BondWise launcher icon is included. The downloadable test APK is a minified, signed release build to reduce app size and installation work.

YTM is an estimate using actual calendar days divided by 365.25, price excluding accrued interest, regular coupon intervals, and principal redemption at face value. It does not apply market-specific day-count, settlement, accrued-interest, or tax treatment conventions. The editable 10% tax default should not be treated as a universal rate.

## Build

Open this folder in Android Studio, or use the repository workflow `.github/workflows/bondwise-apk.yml`. The workflow uploads `BondWise-debug.apk` as an artifact.
