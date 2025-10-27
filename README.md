# 🛰️ MassBeacon

**MassBeacon** is a RuneLite plugin designed to help players find active worlds for otherwise “dead content” — minigames and activities that often lack players. By broadcasting and collecting real-time data from users, MassBeacon provides a simple way to see where the action is happening.

---

## 🧠 Overview

Many Old School RuneScape activities suffer from low player participation — Barbarian Assault, Corporeal Beast, and others can be tough to find teams for.  
MassBeacon aims to fix that by creating a lightweight “activity beacon” system powered by a shared backend. Players using the plugin can see which worlds currently have other players doing the same activity, helping groups form organically.

---

## ⚙️ How It Works

- When a player enters a supported activity area, the plugin **pings** the MassBeacon backend.
- The backend aggregates activity data and serves it to all clients.
- Other players can see which worlds currently have active participants for that content.

Example:
> You open the Barbarian Assault lobby and instantly see which worlds already have players — no more hopping through empty worlds.

---

## 🧩 Features

- 🧭 Automatic activity detection (e.g., Barbarian Assault, Corp Beast)
- 🌍 Real-time world aggregation
- 🕒 Lightweight (no persistent storage or user tracking)
- ⚡ Built for RuneLite
- 🔒 Privacy-safe (no personal data, only world/activity pings)

---

## 💻 Built With

- **Java** (RuneLite Plugin Framework)
- **Cloudflare Workers** backend (KV-based key/value store)
- RESTful API endpoints for world/activity summary

---

## 🚀 Getting Started

### Prerequisites

- [RuneLite](https://runelite.net) client
- Java 11+ (for development)

### Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/yourusername/massbeacon.git
