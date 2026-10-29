# Flowstead privacy policy

_Last updated: 2026-10-29_

Flowstead is an Android app for steady-state process simulation. This policy explains what the app does with your data. In short: your data stays on your phone and on the engine server you choose. The Flowstead project does not receive it.

## What the app stores on your phone

- **Projects:** your flowsheets (streams, unit operations, specifications, compounds) and their last solve results. They are stored in the app's private storage.
- **Settings:** your engine address, unit set, theme and default atmospheric pressure.
- **Engine API key:** stored encrypted with a key held in the Android Keystore. The key never leaves the phone except to authenticate with your engine.

Android's backup may include projects and settings. It never includes the API key, which is excluded because it can't be decrypted on another device.

## What the app sends, and to whom

The app talks only to the **engine address you enter in Settings**. Over HTTPS, it sends:

- the flowsheet you are solving or exporting;
- DWSIM files you choose to import;
- compound searches;
- your API key, in the request header.

The one other address is **GitHub**: when the app opens (unless you turn off **Settings → Check for updates when Flowstead opens**) and when you tap **Settings → Check for updates**, the app asks api.github.com for the latest Flowstead release. The request carries nothing about you or your projects; GitHub sees your IP address, as any website does. If you tap **Update**, Android's download service downloads the release's APK and its checksum file from GitHub into your phone's Downloads folder (on Android 10 and later; the app's own folder on older versions), and the app checks the file against the checksum. Flowstead removes the update files it downloaded before, and nothing else. You install it by tapping Android's "Download complete" notification; Android asks you to confirm. The app itself installs nothing. You can also download the update in your browser instead.

The engine is run by you or your organisation. Its operator can see what you send it. The engine keeps an API-key hash and, if you use stored flowsheets, those flowsheets and results. It does not keep the files you import or export.

## What the app does not do

- No analytics, crash reporting, advertising or tracking SDKs.
- No account with the Flowstead project, and no data sent to the Flowstead project or any third party (the update check above sends no data of yours).
- No location, contacts, camera, microphone or other personal data.

## Files

When you import or export a file, Android's system file picker gives the app access to that one file only.

## Permissions

| Permission | Why |
|---|---|
| Internet | To reach your engine, and GitHub to check for and download updates |
| Network state | To run a queued solve once you're back online |

## Deleting your data

- Delete a project in the app to remove it from the phone.
- Clear the app's storage, or uninstall the app, to remove everything, the API key included.
- Ask your engine operator to revoke the key and to delete any stored flowsheets.

## Children

Flowstead is a professional engineering tool and is not directed at children.

## Changes and contact

Changes to this policy are published with the app's source code. Questions: open an issue in the project's repository.
