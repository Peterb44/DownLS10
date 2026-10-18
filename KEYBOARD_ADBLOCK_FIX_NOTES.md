# Keyboard and ad-block list cleanup

- Address bar keyboard is forced hidden on browser launch and explicitly requested after the user taps the address field.
- Existing cleanup removes persisted data for legacy PGL, NoCoin, Peter Lowe, uBlock, malware, phishing, suspicious/risk, URLhaus and OpenPhish list IDs/aliases.
- The current visible list definitions remain the supported ad lists (StevenBlack, AdGuard/CNAME, Frogeye, Fanboy, AdGuard Ads/Mobile Ads) and the separate optional adult-content list. The removed legacy list IDs are not included in `LISTS`.
- Build and on-device keyboard behavior still need verification in Android Studio/CodeAssist and on the target phone.
