# BondWise UG Android app

BondWise is a standalone Android app in this repository. It starts with an empty list and saves bonds locally on the device. Add a bond with its face value, coupon rate, clean purchase price, maturity date, next coupon date, frequency, and withholding tax rate (default 10%).

Face Value and Purchase Price use the digits-only number pad; coupon and tax percentages use a decimal number pad. UGX amounts include thousands separators. Dates display as `dd-MMM-yyyy` (for example, `21-Oct-2026`) while existing saved dates remain stored in ISO format. Saved bonds entered in My Bonds feed both the My Bonds list and Overview. Bond cards show Purchase Price, Face Value, net annual coupon, and a conditionally labeled red Premium or green Discount relative to face value. Quick Compare lets you enter two temporary bonds and compare coupon rate, face value, Purchase Price, premium or discount, estimated yields, and coupon income without saving them. The Help (?) window contains explanations of tax, yield, purchase price, and premium/discount. Saved-bond comparison remains available separately. The app calculates gross and after-tax current yield and dated-cash-flow YTM estimates, and generates future coupon rows with gross coupon, estimated tax, net coupon, and principal redemption. If maturity does not coincide with a scheduled coupon date, the schedule shows principal at maturity but does not estimate a stub coupon. A BondWise launcher icon is included. The downloadable APK is a minified, signed release build to reduce app size and installation work.

YTM is an estimate using actual calendar days divided by 365.25, price excluding accrued interest, regular coupon intervals, and principal redemption at face value. It does not apply market-specific day-count, settlement, accrued-interest, or tax treatment conventions. The editable 10% tax default should not be treated as a universal rate.

## Build

Open this folder in Android Studio, or use the repository workflow `.github/workflows/bondwise-apk.yml`. The workflow uploads `BondWise-debug.apk` as an artifact.
