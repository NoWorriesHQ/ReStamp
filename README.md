# ReStamp

An Android app that puts the **date taken** and **GPS location** back into photos and videos
after another app's editor stripped them, by copying the tags from the original file.

Built for Google Pixel files (JPEG and MP4) on Android 17, and kept Play Store compatible.

## Status

Step 3 of 4: comparison and editing screens.

| Step | What | State |
|---|---|---|
| 1 | Metadata engine for JPEG + MP4 | ✅ |
| 2 | Match originals to edited copies across folders | ✅ |
| 3 | Comparison and editing screens (bulk copy, manual date, map/city picker) | ✅ this PR |
| 4 | Undo/backup, privacy policy, billing, signed release | next |

## Stack

Kotlin + Jetpack Compose, Google's recommended toolkit, so it tracks new Android versions
best. The project is split into two modules:

- **`core`**: plain Kotlin with no Android dependency. Holds the data model, the
  "never overwrite without confirmation" rule, the copy plan used by the UI, and a
  hand-written MP4 metadata reader/writer (Android has no API for editing video tags).
  Its tests run on any machine in seconds.
- **`app`**: the Android app. Uses AndroidX ExifInterface for JPEG, opens folders via the
  system picker, and has the matching, comparison and editing screens.

## How the engine handles each format

**JPEG**: `DateTimeOriginal` / `OffsetTimeOriginal` / `SubSecTimeOriginal` (and the
`Digitized` copies), plus the standard GPS tags, through ExifInterface. Writes go through a
temp copy so the file is exact even when the EXIF block changes size.

**MP4**: date taken is the creation time in `mvhd` (also written into every `tkhd` and
`mdhd`, like the camera does). Location is the `©xyz` ISO 6709 tag in `moov/udta`, which is
what Pixel and Android's MediaMuxer write and what Google Photos reads. Media data is never
moved or rewritten:

1. If the tag fits in place, it is written in place.
2. Otherwise `moov` is rebuilt into free space right after it, or rewritten if it's the last box.
3. Otherwise the new `moov` is appended at the end and the old one becomes a `free` box.
   The new copy is synced to disk before the old one is retired, so a crash can't leave
   the file without metadata.

## How files are matched

Pick an **originals** folder and an **edited copies** folder (they can be the same folder,
which is how Google Photos saves edits in `DCIM/Camera`). Each edited copy is paired with at most
one original; one original can feed several edited copies. In order of strength:

1. **Same name**, ignoring case and `.jpg`/`.jpeg` differences.
2. **Same name once edit suffixes are removed**, one at a time: `-edited`, `_edit`, `~2`, ` (1)`,
   `-01`, `_exported_…`, `Copy of …`, and similar. Pixel's own shot tags (`.MP`, `.NIGHT`,
   `.PORTRAIT`, `.RAW-01.COVER`, `.TS`) are not edits and are ignored. The longest shared name
   wins, so `Holiday 2-edited` goes to `Holiday 2`, not `Holiday 3`.
3. **Same camera timestamp**: `PXL_20241005_143012345` encodes the capture time (UTC on Pixel), so
   unknown editor suffixes still match. Names that agree only to the second are marked
   "check".

Photos only pair with photos and videos with videos. When two originals fit equally well the app
asks; ties prefer the same file type (a `.jpg` original over a `.dng`). An original never pairs
with a file that looks less edited than itself, so in a shared folder originals stay originals.

The person can unpair any match, pair any edited copy by hand (closest names are suggested
first), and go back to automatic matching. Manual choices always win. The logic is in
`core/match` and has no Android dependency.

## Comparing and fixing tags

Every listed file's tags are read in the background. Each matched pair shows a badge per tag,
with a symbol as well as a colour: ✓ already matches, ✗ missing in the edited copy (will be
added), ≠ different from the original (needs your OK), – the original doesn't have it either.

- **Bulk copy**: tick pairs (all are ticked by default) and tap *Copy tags*. Missing tags are
  added. Replacing a tag that differs is a separate, unticked option per tag, since edited
  copies usually carry the edit time as their date.
- **One file**: tap a pair (or an edited copy with no original) to see both files' values side by
  side, copy one tag or both, or set them by hand: a date and time picker in the phone's time
  zone, and a map picker with city search.
- Any change that would replace an existing tag shows the current and new value first. The
  engine itself also refuses unconfirmed overwrites, so the UI can't skip that step.

The map uses OpenStreetMap through osmdroid, and city search uses OpenStreetMap's Nominatim. Both
are free and need no key. Search runs only when you submit (Nominatim's policy), and sends just
the search text. Only the location picker uses the internet.

## Things learned from real Pixel files

- **Android blanks out GPS** in files read by apps without `ACCESS_MEDIA_LOCATION`. It
  zeroes the `©xyz` box but keeps its size. The engine reports this as
  `locationRedacted`, requests the permission, and asks MediaStore for the original. Sharing a
  file from a phone app can produce such a blanked copy, so send test samples zipped.
- **Edited clips keep a creation time, but it's the edit time.** So copying the date onto an
  edited copy always counts as an overwrite and asks for confirmation.
- **Pixel originals are signed** with C2PA Content Credentials. The engine flags this
  (`hasContentCredentials`), since editing a signed file breaks its signature. The app only
  writes to edited copies.

## Building

Needs JDK 17+ and the Android SDK (platform 37).

```sh
./gradlew :core:test                 # engine tests, no Android SDK needed for this module's code
./gradlew :app:testDebugUnitTest     # JPEG tests (Robolectric)
./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
```

To run the engine against your own phone files (they stay out of the repo):

```sh
RESTAMP_SAMPLES=/path/to/samples ./gradlew :core:test
```

CI builds the debug APK on every push and attaches it to the run.
