# Phonon for Android

Phonon gives TalkBack and other Android text-to-speech apps 102 offline
voices in English, French, German, Spanish and Portuguese. English character
voices are included. It uses Android's speaking rate and pitch controls;
100% is normal speed and faster requests can reach up to three times normal.

Version 1.0.0 is the first public test release. Keep another working speech
engine available while testing Phonon with TalkBack.

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
