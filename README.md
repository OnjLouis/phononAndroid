# Phonon for Android

Phonon gives TalkBack and other Android text-to-speech apps 102 offline
voices in English, French, German, Spanish and Portuguese. English character
voices are included. It uses Android's speaking rate and pitch controls;
100% is normal speed and faster requests can reach up to three times normal.

Version 1.0.2 reads UK currency in English using pounds and pence, including
"one penny." Keep another working speech engine available while testing
Phonon with TalkBack.

Version 1.0.4 lets you preview a voice before importing, correct its language
later, and opt in to playing a sample when the selected voice changes.

Version 1.0.5 supports TalkBack before the first unlock after a reboot. Open
Phonon once after updating and let it prepare the built-in voices. An imported
system voice stays private until unlock; Phonon uses a built-in voice of the
same language while the phone is still locked.

Open **Manage imported voices**, choose **Add voice** and pick a compatible
Phonon .safetensors file in Files. Use **Preview voice** before saving; the
sample uses English until you choose a language. Enter a name and select one of
the languages Phonon already speaks. The new voice is selected for preview;
it does not take over system speech unless you choose **Use this voice for
system speech**. Removing your selected system voice restores automatic
selection. Files remain on your phone, and deleting an import does not
delete the original file.
Choose **Change language** on an existing import to correct the language without
changing the selected system voice.

[Download Phonon for Android from GitHub Releases](https://github.com/OnjLouis/phononAndroid/releases/latest).
Install the APK on your device and follow Android's installer prompt.

Open Phonon to preview a voice or go to text-to-speech settings. To use it
with TalkBack, select Phonon as the preferred engine in those settings. In
Phonon, select a Language and Voice, then tap **Use this voice for system
speech**. Previewing other voices will not change that choice. Apps that
request a specific Phonon voice can still use that voice.

The built-in preview follows the language you choose. Changing languages keeps
the text you typed. Tap **Reset Sample** to return to the built-in text for the
current language.
Turn on **Play when voice changes** to hear a sample when you choose a voice.

**Pause length** applies to previews and system speech. Choose **Shorter** or
**Longer** to change sustained quiet gaps, or keep **Default** for the original
gap timing. This does not change punctuation or the speaking rate.

**Help** is in the top bar. The **About** section links to
[Gradium](https://gradium.ai/) and [Andre's software catalogue](https://onj.me/software/).

Requires a 64-bit ARM Android device with Android 9 or later. Voice setup
and speech run entirely on your device. Phonon does not send your speech text
or audio anywhere. **Check for updates** contacts GitHub and offers to download
a signed APK when an update is available.

When reporting a problem, include your phone model and Android version. For
speech delays or dropouts, mention the voice, approximate speaking rate, and
whether it happened while swiping, navigating by character, or reading a passage.

Uses [Gradium Phonon](https://gradium.ai/phonon) and the [xn-ptts runtime](https://github.com/gradium-ai/xn-ptts).
The packaged multilingual model is available for non-commercial use under
[CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/).
Model, voice catalogue, runtime and Sonic notices are included in the APK.
The Android application code is [MIT licensed](LICENSE).

The arm64 runtime is built from `xn-ptts` 0.7.0. For the shipped runtime,
apply `native/ptts-android-inline.patch` and then
`native/ptts-english-sterling.patch` and `native/ptts-android-voice-import.patch`
before building `ptts-ffi`.
