# Airbus Bangalore Job Watcher

An Android 12+ job watcher. It currently queries Airbus's public Workday jobs API, but its data layer is built around source adapters so additional companies and ATS platforms can be added without changing the UI or database model.

## Chosen approach

- **Language/UI:** Kotlin, Jetpack Compose, Material 3.
- **Networking:** Retrofit + Kotlin serialization, using a `POST` request.
- **Sources:** `JobSourceConfig` describes a company/source; `JobSourceAdapter` converts that source's response into a common job record. Airbus Workday is the first adapter.
- **Persistence:** Room stores a job's immutable Workday requisition ID and its snapshot.
- **Scheduling:** WorkManager runs every 30 minutes (Android's minimum periodic interval is 15 minutes, and its timing is intentionally inexact). A manual refresh is also available.
- **Android target:** `minSdk 31` (Android 12), `targetSdk 35`.

The app is designed so the exact Workday endpoint and filter JSON are supplied in `local.properties`, not committed to source control. This avoids guessing at a location ID or publishing it in the app.

## Adding a future source

Add a `JobSourceConfig` to `JobSourceRegistry`, then implement a `JobSourceAdapter` for the platform (for example Greenhouse or Lever). The adapter returns `FetchedJob` values, and `JobRepository` assigns the globally unique identity `sourceId:sourceJobId`, updates only that source's jobs, and maintains an independent notification baseline for it.

## Configure

Add these entries to `local.properties` (replace the examples with the request captured from the Airbus portal's browser network inspector):

```properties
AIRBUS_API_URL=https://ag.wd3.myworkdayjobs.com/wday/cxs/airbus/Airbus/jobs
AIRBUS_REQUEST_JSON={"limit":20,"offset":0,"searchText":"","appliedFacets":{"locations":["YOUR_BANGALORE_LOCATION_ID"]}}
```

`AIRBUS_REQUEST_JSON` must be JSON and must contain `limit` and `offset`. The app begins at offset `0` and follows subsequent pages while `total` indicates more results. The location facet must be the one returned by Airbus Workday for Bangalore; retain any country/India facet required by the portal's request.

## What counts as new

A job is new when its stable Workday ID (`bulletFields` / `externalPath` / job requisition ID, in that order) is absent from the Room database. On the very first successful refresh, the app saves the current jobs silently: it does not notify for the historical backlog. Later runs notify once per newly observed ID. A changed title or description does not re-notify.

## Reading and applying

The main list reads stored jobs offline. Selecting a row opens the official Airbus Workday job URL in a Custom Tab, where the candidate can read the complete description and apply. No login credentials are stored or sent by this app.

## Important delivery constraint

WorkManager is battery-friendly rather than exact; Android may defer a 30-minute run during Doze or restrictive device modes. If a guaranteed, low-latency alert is essential, place the identical request and de-duplication database on a server, and deliver Firebase Cloud Messaging notifications to the app. The included app is intentionally the simple, privacy-preserving device-only option.
