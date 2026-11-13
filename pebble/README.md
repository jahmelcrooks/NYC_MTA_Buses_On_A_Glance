# NYC Buses On A Glance — Pebble

Nearby NYC bus arrivals on any Pebble: original Pebble and Steel, Time, Time Round, Pebble 2,
Time 2, Pebble 2 Duo and the newer round Pebble.

## How it works
A Pebble has no GPS or internet of its own, so this version needs the phone:

- **`src/pkjs/` (runs in the Pebble app on your phone):** gets your location, finds stops
  within your radius, fetches live arrivals from MTA Bus Time, and groups them the same way
  as the Wear OS app (nearest stop wins, starred buses first). Starred buses and settings are
  kept on the phone.
- **`src/c/` (runs on the watch):** shows the list. Arrival times are sent as timestamps, so
  the minutes count down on the watch and the list refreshes every minute while it's open.

## Using it
- **Up / Down:** scroll the nearby buses (`*` marks a starred one).
- **Select:** open a bus (next 3 times with clock times, "stops away", destination, stop).
- **Hold Select:** star or unstar it. Starred buses are listed first.
- **Settings** (in the Pebble phone app, under this app): your MTA Bus Time API key
  ([get one free](https://register.developer.obanyc.com/)), search radius
  (0.25 / 0.5 / 0.75 / 1 mi), and km instead of miles.

## Install
Download `nyc-buses-pebble` (the `.pbw`) from the latest **Pebble** run under the repo's
Actions tab. Open it on your phone with the Pebble app, or with the SDK:
`pebble install --phone <your phone's IP>`.

## Build and test
```
uv tool install pebble-tool      # Python 3.10–3.13
pebble sdk install latest
pebble build                     # build/pebble.pbw
pebble install --emulator basalt # try it in the emulator
npm test                         # unit tests for the phone-side logic (plain Node)
```
`PEBBLE_DEMO=1 pebble build` makes a build with sample buses, used for screenshots.

CI (`.github/workflows/pebble.yml`) runs the tests, builds for every platform, and takes
emulator screenshots.

Not affiliated with or endorsed by the MTA. MIT licensed, like the rest of the repo.
