# JobChan

JobChan is an Android 12+ multi-company job watcher. It polls public career boards, normalizes postings into one local feed, and notifies the user when a selected company has a new job matching their location preferences.

## Current capabilities

- Kotlin, Jetpack Compose, Material 3, Room, Retrofit, and WorkManager.
- A company sidebar with persistent selection and custom-source management.
- Public source adapters for Workday and Greenhouse.
- Built-in sources: Airbus, Razorpay, Discord, Anthropic, and Stripe. Some future companies are listed as unavailable until their adapter is implemented.
- Add a custom Greenhouse or Workday company by supplying its public endpoint; Workday also needs its public request-body template.
- A location dropdown for India, Remote, Other countries, and selectable country presets.
- India postings are always ranked above Remote and other-country postings.
- Each company has a separate first-refresh baseline, so enabling a new company does not notify for its historical backlog.
- Tapping a card opens the official job application page.

## Source architecture

`JobSourceConfig` describes a company board and `JobSourceAdapter` converts provider-specific data to `FetchedJob`. `JobRepository` then stores a normalized `JobEntity` using this globally unique identity:

```text
sourceId:sourceJobId
```

This avoids clashes when different companies use the same job/requisition ID. New providers require an adapter; entering an arbitrary URL does not make an unsupported API compatible.

## Location filtering without provider IDs

Users never need to discover or enter opaque Workday location IDs.

- **Workday:** common location facets in a supplied request template are removed. The app searches with readable terms such as `India`, `Remote`, or `Germany`, then verifies each returned job locally.
- **Greenhouse:** the public board is fetched and jobs are filtered locally.
- **Other countries:** with no countries selected, JobChan searches a practical global set. Selecting countries narrows it to those choices.

The location normalizer recognizes country names and common city-only labels. For India, this includes Bengaluru/Bangalore, Mumbai, Delhi NCR, Hyderabad, Pune, Chennai, Kolkata, Ahmedabad, Kochi, Jaipur, and more.

Because Workday searches are narrowed results rather than a complete global snapshot, the app does not infer that a missing Workday job has closed. A failed source request also never deletes cached jobs.

## Add a custom company

From the sidebar, select **Add company** and enter:

- Company name
- Adapter type: `Greenhouse` or `Workday`
- Public API endpoint
- For Workday only: public request-body JSON

Do not add login cookies, API secrets, or private endpoints. The app is designed for anonymous public career boards.

The built-in companies use public, anonymous endpoints. Workday request bodies always require valid JSON containing `limit` and `offset`; JobChan removes common provider location facets and applies the selected readable location filters.

## Notifications and scheduling

A job is new when its `sourceId:sourceJobId` is not in the local Room database. Only newly observed jobs that match the currently selected location filters trigger a notification.

WorkManager schedules checks every 30 minutes. Android may defer periodic work for battery saving or Doze mode; it is not an exact scheduler. For guaranteed near-real-time alerts, move source polling and de-duplication to a backend and send Firebase Cloud Messaging notifications.

## Build

Open the project in Android Studio with Java 17+ and run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
