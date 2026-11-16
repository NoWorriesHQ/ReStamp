# Publishing ReStamp on Google Play

This is the checklist for the first release, with the answers for Play Console's forms.

## 1. One-time setup (you)

1. **Create a Play Console developer account** at https://play.google.com/console (a one-time US$25 fee, plus identity checks). For a new *personal* account, Google requires a closed test with at least 12 testers for 14 days before production. An *organization* account skips this but needs a D-U-N-S number.
2. **Create the app**: name it ReStamp, set it as an App, make it Free, and accept the declarations.
3. **Create the upload key** on a computer with Java installed:
   ```
   keytool -genkeypair -v -keystore restamp-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Keep the file and both passwords somewhere safe, such as a password manager. Never put them in the repo.
4. **Add four GitHub secrets** under the repo's Settings, Secrets and variables, Actions:
   - `RESTAMP_UPLOAD_KEYSTORE_BASE64`: the output of `base64 -w0 restamp-upload.jks` (on a Mac, `base64 -i restamp-upload.jks`)
   - `RESTAMP_UPLOAD_STORE_PASSWORD`
   - `RESTAMP_UPLOAD_KEY_ALIAS`: `upload`
   - `RESTAMP_UPLOAD_KEY_PASSWORD`

   After that, every CI run makes a signed `restamp-release-bundle` (.aab) that Play accepts. Play App Signing is on by default and keeps the real app signing key at Google.

## 2. In-app product

Under Monetize, Products, In-app products, create:
- **Product ID:** `bulk_copy` (this must match exactly)
- **Name:** Bulk copy
- **Description:** Copy date and location for a whole folder at once.
- **Price:** your choice (for example US$3.99), then activate it.

Play only shows products once a build containing the billing library has been uploaded to a testing track. Add your own Google account as a license tester, under Settings, License testing, so you can buy without being charged.

## 3. Store listing

- **Short description (80 characters max):** Put the date taken and GPS back into edited photos and videos.
- **Full description:**
  > Edited a photo or video in another app and lost its date and location? ReStamp copies them back from the original.
  >
  > • View and edit the date taken and location inside any JPEG photo or MP4 video
  > • Copy tags from an original to its edited copy
  > • Bulk copy: pick your originals folder and edited folder, and ReStamp matches files by name (PXL_ timestamps, "-edited", "(1)" and more) and fixes them all at once
  > • Set a place by searching or tapping a map
  > • Never overwrites existing tags without asking, and every change can be undone for 30 days
  > • Keeps motion photo videos, Ultra HDR and everything else in the file intact
  >
  > Everything happens on your phone. No account, no ads, no tracking. Bulk copy is a one-time purchase; everything else is free.
- **App icon:** use `play-icon-512.png`, the 512×512 PNG from the project files.
- **Feature graphic:** 1024×500. This is still needed.
- **Screenshots:** at least 2 phone screenshots. Take them from the test build.
- **Category:** Photography.
- **Privacy policy URL:** needs a public link. The repo is private, so host `docs/privacy-policy.md` somewhere public, such as a free GitHub Pages site from a separate public repo or a page on your website.

## 4. App content forms

**Data safety:**
- Does the app collect or share user data? **Yes**, only for the map search.
- **App activity, "Other user-generated content"** (the place search text): collected. Not shared (sending it to a service provider that processes it for you doesn't count as sharing). Processed ephemerally: yes. Optional: yes. Purpose: App functionality.
- **Photos and videos:** not collected. They never leave the device, and on-device processing doesn't count as collection.
- **Location:** not collected. The app doesn't read the device's location.
- **Financial info:** purchase history is handled by Google Play, so it isn't declared by the app.
- Is data encrypted in transit? **Yes** (HTTPS).
- Can users request deletion? Nothing is stored off-device. Answer **No**, because there's nothing to delete. Undo copies are deleted with the app.

**Other forms:**
- **Content rating:** answer the IARC questionnaire. There's no violence, no user-to-user contact and no gambling, so it comes out as Everyone or PEGI 3.
- **Target audience:** 18+ (or 13+). Not designed for children.
- **Ads:** No ads.
- **Permissions declaration:** ACCESS_MEDIA_LOCATION may prompt a question. The answer is "core feature: reading the location stored in the user's photos to copy it to edited copies".

## 5. Release

1. Download the signed `.aab` from the latest CI run on `main`.
2. Upload it to **Internal testing** first and install it from Play. Check that the purchase works with a license tester.
3. If you have a personal account, run the closed test: 12+ testers for 14 days.
4. Then promote to **Production**.

## Known limits to revisit

- **Map tiles:** OpenStreetMap's tile servers are free for light use, but their policy discourages heavy use by published apps. If ReStamp gets popular, switch to a hosted tile provider (MapTiler, Stadia, Thunderforest). Most have a free tier and need an API key.
- **Place search:** Nominatim allows at most 1 request per second across all users of an app. For many users, move to a paid geocoder or a self-hosted one.
