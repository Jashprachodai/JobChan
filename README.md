# JobChan 👁️⚡

An intelligent, minimalist Android job watcher and real-time notification engine for public career portals. **JobChan** polls public company career boards, normalizes job listings across diverse applicant tracking systems (ATS), and triggers instant notifications whenever new postings matching your location preferences are detected.

---

## 🌟 Key Capabilities

- **🏢 Multi-Company Tracking:** Out-of-the-box support for major tech and aerospace portals (*Airbus*, *Razorpay*, *Discord*, *Anthropic*, *Stripe*).
- **⚡ Strict Recency Sorting:** Epoch-timestamp normalization ensures newest postings (*Posted Today*, *Posted Yesterday*) **always appear at the very top** of your feed.
- **📍 Smart Location Normalizer:** Automatically categorizes Indian tech hubs (*Bengaluru/Bangalore*, *Gurugram/Gurgaon*, *Hyderabad*, *Pune*, *Noida*, *Mumbai*, *Chennai*, *Delhi NCR*, *Kolkata*, *Kochi*, *Jaipur*, etc.), *Remote*, and *International* positions without requiring opaque ATS location UUIDs.
- **🎨 Minimalist Material 3 UI:** Built with custom typography (**Oswald** for high-impact titles & counts, **Bai Jamjuree** for metadata), company monogram avatars, search bar filtering, and a slide-out sidebar drawer with **live job count badges**.
- **➕ Dynamic Custom Company Addition:** Easily add any new Greenhouse or Workday company board on the fly right inside the app.
- **🔔 Privacy-Preserving Notifications:** Background polling via WorkManager checks for new postings without sending your data to external third-party servers. Each company maintains its own baseline, preventing notification spam for historical backlogs.

---

## 📐 Architecture Overview

JobChan decouples company metadata from retrieval logic using a clean **Provider Pattern**:

```text
┌──────────────────────────────────────────────────────────┐
│                      JobRepository                       │
└────────────────────────────┬─────────────────────────────┘
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
┌───────────────────────┐         ┌───────────────────────┐
│    WorkdayAdapter     │         │   GreenhouseAdapter   │
└───────────┬───────────┘         └───────────┬───────────┘
            │                                 │
            └────────────────┬────────────────┘
                             ▼
                 ┌───────────────────────┐
                 │  JobEntity (Room DB)  │
                 └───────────────────────┘
```

- **`JobSourceConfig`**: Represents a company board configuration (ID, adapter type, endpoint, payload).
- **`JobSourceAdapter`**: Converts raw ATS JSON responses into a unified `FetchedJob` data structure.
- **`JobEntity`**: Persists normalized listings in Room DB using a globally unique compound key:
  ```text
  jobKey = "$sourceId:$sourceJobId"
  ```
  *Example:* `workday:airbus:JR10445966` or `greenhouse:stripe:7150212`

---

## 📍 Location Normalizer (No Provider IDs Required)

You never need to manually discover or inspect network requests for Workday location UUIDs:

- **Workday:** Common location facets in request templates are stripped automatically. The app queries Workday using human-readable terms (*India*, *Bangalore*, *Gurugram*, *Hyderabad*, *Pune*, *Remote*, etc.) and verifies each returned posting locally.
- **Greenhouse & Lever:** The public job board is fetched and filtered client-side against an expanded location alias dictionary.
- **Location Regions:** Filter seamlessly by **India**, **Remote**, or **Other Countries** with custom country preset options (*United States*, *United Kingdom*, *Germany*, *Canada*, *Singapore*, *Japan*, etc.).

---

## ➕ Adding Custom Companies in the App

Open the sidebar drawer $\rightarrow$ tap the **`+`** icon $\rightarrow$ fill out the details:

1. **Company Name** *(e.g. Google)*
2. **Adapter Type** *(Greenhouse or Workday)*
3. **API Endpoint URL** *(e.g. `https://boards-api.greenhouse.io/v1/boards/google/jobs?content=true`)*
4. **Workday Request Body** *(Workday only: paste standard POST JSON template)*

> [!NOTE]
> JobChan is designed strictly for anonymous, public career boards. Do not add private login cookies or API keys.

---

## 🛠️ Tech Stack & System Requirements

| Component | Technology |
| :--- | :--- |
| **Minimum OS** | Android 12 (API 31+) |
| **Target OS** | Android 15 (API 35) |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Custom Fonts** | Oswald & Bai Jamjuree (via `androidx.compose.ui.text.googlefonts`) |
| **Image Loading** | Coil (`io.coil-kt:coil-compose`) |
| **Database** | Room (`androidx.room`) |
| **Networking** | Retrofit + `kotlinx.serialization` + OkHttp |
| **Background Sync** | WorkManager (`androidx.work`) |

---

## 🛠️ Building & Running

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/JobChan.git
   cd JobChan
   ```
2. Open the project in **Android Studio (Ladybug or newer)** with **JDK 17+**.
3. Build the debug APK via Gradle:
   ```bash
   # On Windows
   .\gradlew.bat :app:assembleDebug

   # On macOS / Linux
   ./gradlew :app:assembleDebug
   ```
4. The generated APK will be available at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License

Distributed under the **MIT License**. See `LICENSE` for more information.
