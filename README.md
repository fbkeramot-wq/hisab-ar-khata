# হিসাবের খাতা

মুদি দোকানের বাকির খাতা আর দৈনিক বেচা-খরচের অ্যাপ।

- Google দিয়ে লগইন, প্রতিটা হিসাব Firebase-এ অটো অনলাইন সেভ
- নেট না থাকলেও চলে, নেট এলে নিজে থেকে সেভ হয়
- নতুন ভার্সন এলে অ্যাপে নোটিফিকেশন যায়, চাপ দিলে অ্যাপের ভেতরেই আপডেট

## নতুন ভার্সন দেওয়ার নিয়ম
1. `web/app-body.html` বদলালে `python3 web/build_android.py` চালাও
2. `app/build.gradle.kts`-এ `versionCode` ১ বাড়াও আর `versionName` বদলাও (যেমন 1.0.1)
3. `RELEASE_NOTES.md`-এর প্রথম লাইনে কী বদলেছে লেখো (এটাই নোটিফিকেশনে দেখাবে)
4. main-এ push করলে GitHub নিজেই APK বানিয়ে Releases-এ দেবে

সর্বশেষ APK: [Releases](https://github.com/fbkeramot-wq/hisab-ar-khata/releases/latest)
